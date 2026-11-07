package com.hassaku.plamotranslate.data.inference

import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device PLaMo via llama-android (llama.cpp AAR).
 * Uses absolute file path in app-private storage.
 */
@Singleton
class PlamoLlamaEngine @Inject constructor() : TranslationEngine {

    companion object {
        private const val TAG = "PlamoLlamaEngine"
        // Keep context modest: large ctx inflates KV memory and can tip OOM on mid-range phones.
        private const val DEFAULT_CTX = 1024
        private const val MAX_GEN_TOKENS = 512
        const val MAX_INPUT_CHARS = 2000
        private const val MIN_MODEL_BYTES = 200_000_000L
    }

    private val mutex = Mutex()
    private var model: LlamaModel? = null
    @Volatile private var modelLoaded = false
    private var currentModelPath: String? = null
    private val loading = AtomicBoolean(false)
    private val translating = AtomicBoolean(false)

    override val isReady: Boolean
        get() = modelLoaded && model != null

    override val engineName: String
        get() = if (isReady) "PLaMo-3-0.8B (llama-android)" else "PLaMo (not loaded)"

    override suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (isReady && currentModelPath == modelPath) {
            return@withContext Result.success(Unit)
        }
        if (!loading.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("ロード処理中です"))
        }
        try {
            mutex.withLock {
                val file = File(modelPath)
                if (!file.exists()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("モデルファイルがありません")
                    )
                }
                val sizeMb = file.length() / 1_000_000
                if (file.length() < MIN_MODEL_BYTES) {
                    return@withContext Result.failure(
                        IllegalArgumentException(
                            "モデルが小さすぎます (${sizeMb}MB)。再DLしてください"
                        )
                    )
                }
                if (!file.canRead()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("モデルを読めません: ${file.absolutePath}")
                    )
                }
                // Quick GGUF magic check
                val magicOk = try {
                    file.inputStream().use { ins ->
                        val m = ByteArray(4)
                        ins.read(m) == 4 && m.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
                    }
                } catch (_: Exception) { false }
                if (!magicOk) {
                    return@withContext Result.failure(
                        IllegalArgumentException("GGUF形式ではありません。モデルを削除して再DLしてください")
                    )
                }

                val rt = Runtime.getRuntime()
                System.gc()
                val maxMb = rt.maxMemory() / 1_000_000
                val freeMb = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1_000_000
                Log.i(
                    TAG,
                    "Loading ${sizeMb}MB from ${file.absolutePath} " +
                        "jvmMax=${maxMb}MB jvmFree≈${freeMb}MB"
                )

                model?.let { old ->
                    runCatching { Llama.releaseModel(old) }
                }
                model = null
                modelLoaded = false

                // Heuristic: model + ~30% overhead should fit in JVM max (rough; native is off-heap)
                if (maxMb > 0 && sizeMb > maxMb) {
                    Log.w(TAG, "Model ${sizeMb}MB may exceed JVM max ${maxMb}MB")
                }

                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val loaded = try {
                    Llama.loadModel(
                        modelPath = file.absolutePath,
                        config = LlamaConfig(
                            contextSize = DEFAULT_CTX,
                            threads = threads,
                            gpuLayers = 0, // CPU only — free tier has no Vulkan
                        ),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Llama.loadModel threw", e)
                    val detail = e.message ?: e.javaClass.simpleName
                    val hint = when {
                        detail.contains("enough RAM", ignoreCase = true) ||
                            detail.contains("OutOfMemory", ignoreCase = true) ||
                            detail.contains("Failed to load model", ignoreCase = true) ->
                            "\n\n対処: 他アプリをすべて終了 → 再試行。" +
                                "まだダメならモデル削除して小さい量子化(Q2/Q3)を再DL。" +
                                "(現在 ${sizeMb}MB / JVM上限≈${maxMb}MB)"
                        else -> ""
                    }
                    throw RuntimeException("ネイティブロード失敗: $detail$hint", e)
                }

                model = loaded
                modelLoaded = true
                currentModelPath = modelPath
                Log.i(TAG, "Model loaded OK threads=$threads ctx=$DEFAULT_CTX")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadModel failed", e)
            model = null
            modelLoaded = false
            currentModelPath = null
            Result.failure(
                RuntimeException(e.message ?: "モデルの読み込みに失敗しました", e)
            )
        } finally {
            loading.set(false)
        }
    }

    override fun unloadModel() {
        model?.let { runCatching { Llama.releaseModel(it) } }
        model = null
        modelLoaded = false
        currentModelPath = null
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        style: Style
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isReady) {
            return@withContext Result.failure(IllegalStateException("モデルがロードされていません"))
        }
        if (!translating.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("翻訳処理中です"))
        }
        val clipped = if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) else text
        val handle = model
        if (handle == null) {
            translating.set(false)
            return@withContext Result.failure(IllegalStateException("エンジンが初期化されていません"))
        }
        try {
            mutex.withLock {
                val prompt = buildPrompt(clipped, sourceLang, targetLang, style)
                Log.d(TAG, "prompt chars=${prompt.length}")
                val result = Llama.complete(
                    model = handle,
                    prompt = prompt,
                    systemPrompt = "",
                    maxTokens = MAX_GEN_TOKENS,
                )
                Result.success(postProcess(result.text))
            }
        } catch (e: Exception) {
            Log.e(TAG, "translate failed", e)
            Result.failure(e)
        } finally {
            translating.set(false)
        }
    }

    private fun buildPrompt(text: String, sourceLang: String, targetLang: String, style: Style): String {
        val src = normalizeLang(sourceLang)
        val tgt = normalizeLang(targetLang)
        val styleHint = when {
            tgt.equals("Japanese", ignoreCase = true) && style == Style.POLITE ->
                "\nWrite the translation in polite Japanese."
            tgt.equals("Japanese", ignoreCase = true) && style == Style.PLAIN ->
                "\nWrite the translation in plain Japanese."
            else -> ""
        }
        return buildString {
            appendLine("<|plamo:op|>dataset")
            appendLine("translation")
            appendLine()
            appendLine("<|plamo:op|>input lang=$src")
            appendLine(text.trim())
            append("<|plamo:op|>output lang=$tgt")
            if (styleHint.isNotEmpty()) append(styleHint)
            appendLine()
        }
    }

    private fun normalizeLang(lang: String): String = when (lang.lowercase().trim()) {
        "ja", "jp", "japanese", "日本語" -> "Japanese"
        "en", "english", "英語" -> "English"
        "zh", "chinese", "中文", "中国語" -> "Chinese"
        "ko", "korean", "韓国語" -> "Korean"
        else -> lang.replaceFirstChar { it.uppercase() }
    }

    private fun postProcess(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("<\\|plamo:op\\|>.*"), "")
            .replace(Regex("<\\|plamo:reserved:[^|]*\\|>"), "")
            .trim()
        return s
    }
}
