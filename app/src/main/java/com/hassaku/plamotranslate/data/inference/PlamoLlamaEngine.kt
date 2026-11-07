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
        private const val DEFAULT_CTX = 2048
        private const val MAX_GEN_TOKENS = 1024
        const val MAX_INPUT_CHARS = 4000
        private const val MIN_MODEL_BYTES = 300_000_000L
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
                if (file.length() < MIN_MODEL_BYTES) {
                    return@withContext Result.failure(
                        IllegalArgumentException(
                            "モデルが小さすぎます (${file.length() / 1_000_000}MB)。再DLしてください"
                        )
                    )
                }
                if (!file.canRead()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("モデルを読めません: ${file.absolutePath}")
                    )
                }

                Log.i(TAG, "Loading ${file.length() / 1_000_000}MB from ${file.absolutePath}")

                model?.let { old ->
                    runCatching { Llama.releaseModel(old) }
                }
                model = null
                modelLoaded = false

                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val loaded = try {
                    Llama.loadModel(
                        modelPath = file.absolutePath,
                        config = LlamaConfig(
                            contextSize = DEFAULT_CTX,
                            threads = threads,
                        ),
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Llama.loadModel threw", e)
                    throw RuntimeException(
                        "ネイティブロード失敗: ${e.message ?: e.javaClass.simpleName}",
                        e
                    )
                }

                model = loaded
                modelLoaded = true
                currentModelPath = modelPath
                Log.i(TAG, "Model loaded OK threads=$threads")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadModel failed", e)
            model = null
            modelLoaded = false
            currentModelPath = null
            val msg = when {
                e.message?.contains("OutOfMemory", ignoreCase = true) == true ->
                    "メモリ不足でロードできません。他アプリを閉じて再試行"
                e.message?.contains("unsupported", ignoreCase = true) == true ||
                    e.message?.contains("unknown model", ignoreCase = true) == true ->
                    "このエンジンはモデル形式を認識できません: ${e.message}"
                else -> e.message ?: "モデルの読み込みに失敗しました"
            }
            Result.failure(RuntimeException(msg, e))
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
