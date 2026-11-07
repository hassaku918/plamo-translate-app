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
        /** Translation outputs are short; keep generation tight for latency. */
        private const val MAX_GEN_TOKENS = 128
        const val MAX_INPUT_CHARS = 1200
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
            // Pixel-class phones: use more CPU for prompt eval
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 6)
            val result = NativeLlama.load(
                path = file.absolutePath,
                nCtx = DEFAULT_CTX,
                nThreads = threads,
            )
            if (result.isSuccess) {
                currentModelPath = modelPath
                Log.i(TAG, "load ok threads=$threads")
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
        try {
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
            val t0 = System.currentTimeMillis()
            val result = NativeLlama.complete(prompt, MAX_GEN_TOKENS)
            Log.i(TAG, "complete took ${System.currentTimeMillis() - t0}ms")
            result.map { postProcess(it) }
        } catch (e: Exception) {
            Log.e(TAG, "translate failed", e)
            Result.failure(e)
        } finally {
            translating.set(false)
        }
    }

    private fun buildPrompt(text: String, sourceLang: String, targetLang: String, style: Style): String {
        // Official PFML3 format (model card pfnet/plamo-3-translate-0.8b-GGUF).
        // Style is passed via the PFML3 form key (plain/polite, Japanese output
        // only — enforced inside Pfml3), not as a natural-language hint.
        return Pfml3.buildPrompt(
            sourceText = text.trim(),
            sourceLang = toLangCode(sourceLang),
            targetLang = toLangCode(targetLang),
            form = when (style) {
                Style.POLITE -> Pfml3.FORM_POLITE
                Style.PLAIN -> Pfml3.FORM_PLAIN
            },
        )
    }

    /**
     * Convert the UI/engine language names ("English", "Japanese", codes, ...)
     * to the language codes PFML3 expects ("en", "ja", ...).
     * Unknown values are passed through lower-cased as a best effort.
     */
    private fun toLangCode(lang: String): String = when (lang.lowercase().trim()) {
        "ja", "jp", "japanese", "日本語" -> "ja"
        "en", "english", "英語" -> "en"
        "zh", "zh-hans", "chinese", "中文", "中国語" -> "zh-hans"
        "zh-hant", "中国語（繁体字）" -> "zh-hant"
        "ko", "korean", "韓国語" -> "ko"
        else -> lang.lowercase().trim()
    }

    private fun postProcess(raw: String): String {
        var s = raw.trim()
        // The model ends a translation by emitting a PFML3 stop token
        // (<|plamo:tag|> / <|plamo:bos|>). JNI stops there, but if any token
        // text survived into the output, cut everything from it onward —
        // that is markup, not translation. Do NOT truncate at blank lines:
        // multi-paragraph translations are legitimate output.
        val stopIdx = s.indexOf("<|plamo:")
        if (stopIdx >= 0) {
            s = s.substring(0, stopIdx)
        }
        s = s.replace(Regex("<\\|plamo:reserved:[^|]*\\|>"), "")
        return s.trim()
    }
}
