package com.hassaku.plamotranslate.data.inference

import android.util.Log
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
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
 * Uses absolute file path (app-private storage) — no ContentResolver URI required.
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
    private var modelHandle: Long = 0L
    @Volatile private var modelLoaded = false
    private var currentModelPath: String? = null
    private val loading = AtomicBoolean(false)
    private val translating = AtomicBoolean(false)

    override val isReady: Boolean
        get() = modelLoaded && modelHandle != 0L

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

                // Release previous
                if (modelHandle != 0L) {
                    runCatching { Llama.releaseModel(modelHandle) }
                    modelHandle = 0L
                    modelLoaded = false
                }

                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val handle = try {
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

                if (handle == 0L) {
                    throw RuntimeException(
                        "ネイティブロード失敗 (handle=0)。" +
                            "この端末/ビルドが PLaMo 3 アーキテクチャ未対応の可能性があります"
                    )
                }

                modelHandle = handle
                modelLoaded = true
                currentModelPath = modelPath
                Log.i(TAG, "Model loaded OK handle=$handle threads=$threads")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadModel failed", e)
            modelHandle = 0L
            modelLoaded = false
            currentModelPath = null
            val msg = when {
                e.message?.contains("OutOfMemory", ignoreCase = true) == true ->
                    "メモリ不足でロードできません。他アプリを閉じて再試行"
                e.message?.contains("unsupported", ignoreCase = true) == true ||
                    e.message?.contains("unknown model", ignoreCase = true) == true ||
                    e.message?.contains("アーキテクチャ", ignoreCase = true) == true ->
                    "このエンジンは PLaMo 3 を認識できません: ${e.message}"
                else -> e.message ?: "モデルの読み込みに失敗しました"
            }
            Result.failure(RuntimeException(msg, e))
        } finally {
            loading.set(false)
        }
    }

    override fun unloadModel() {
        if (modelHandle != 0L) {
            runCatching { Llama.releaseModel(modelHandle) }
        }
        modelHandle = 0L
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
        val handle = modelHandle
        if (handle == 0L) {
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
                    systemPrompt = null,
                    maxTokens = MAX_GEN_TOKENS,
                )
                val raw = result.text
                Result.success(postProcess(raw))
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
        // Official PLaMo 3 Translate prompt format
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
