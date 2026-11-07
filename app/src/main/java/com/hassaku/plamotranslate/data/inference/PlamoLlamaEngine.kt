package com.hassaku.plamotranslate.data.inference

import android.util.Log
import com.hassaku.llamajni.NativeLlama
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device PLaMo via official llama.cpp (compiled into :llamajni).
 */
@Singleton
class PlamoLlamaEngine @Inject constructor() : TranslationEngine {

    companion object {
        private const val TAG = "PlamoLlamaEngine"
        private const val DEFAULT_CTX = 1024
        private const val MAX_GEN_TOKENS = 512
        const val MAX_INPUT_CHARS = 2000
        private const val MIN_MODEL_BYTES = 200_000_000L
    }

    private val loading = AtomicBoolean(false)
    private val translating = AtomicBoolean(false)
    private var currentModelPath: String? = null

    override val isReady: Boolean
        get() = NativeLlama.isLoaded

    override val engineName: String
        get() = if (isReady) "PLaMo-3-0.8B (llama.cpp)" else "PLaMo (not loaded)"

    override suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (isReady && currentModelPath == modelPath) {
            return@withContext Result.success(Unit)
        }
        if (!loading.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("ロード処理中です"))
        }
        try {
            val file = File(modelPath)
            if (!file.exists()) {
                return@withContext Result.failure(IllegalArgumentException("モデルファイルがありません"))
            }
            if (file.length() < MIN_MODEL_BYTES) {
                return@withContext Result.failure(
                    IllegalArgumentException("モデルが小さすぎます (${file.length() / 1_000_000}MB)")
                )
            }
            val magicOk = try {
                file.inputStream().use { ins ->
                    val m = ByteArray(4)
                    ins.read(m) == 4 && m.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
                }
            } catch (_: Exception) { false }
            if (!magicOk) {
                return@withContext Result.failure(
                    IllegalArgumentException("GGUF形式ではありません。再DLしてください")
                )
            }

            Log.i(TAG, "Loading ${file.length() / 1_000_000}MB ${file.absolutePath}")
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val result = NativeLlama.load(
                path = file.absolutePath,
                nCtx = DEFAULT_CTX,
                nThreads = threads,
            )
            if (result.isSuccess) {
                currentModelPath = modelPath
                Log.i(TAG, "load ok")
            } else {
                currentModelPath = null
                Log.e(TAG, "load failed: ${result.exceptionOrNull()?.message}")
            }
            result
        } catch (e: Exception) {
            currentModelPath = null
            Log.e(TAG, "load threw", e)
            Result.failure(e)
        } finally {
            loading.set(false)
        }
    }

    override fun unloadModel() {
        // fire-and-forget bridge; ViewModel also calls suspend unload
        try {
            // native unload is synchronized inside JNI
            // Use runBlocking-free path: NativeLlama.unload is suspend — call via thread
            java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                try {
                    kotlinx.coroutines.runBlocking { NativeLlama.unload() }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        currentModelPath = null
    }

    suspend fun unloadModelSuspending() {
        NativeLlama.unload()
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
        try {
            val clipped = if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) else text
            val prompt = buildPrompt(clipped, sourceLang, targetLang, style)
            val result = NativeLlama.complete(prompt, MAX_GEN_TOKENS)
            result.map { postProcess(it) }
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
