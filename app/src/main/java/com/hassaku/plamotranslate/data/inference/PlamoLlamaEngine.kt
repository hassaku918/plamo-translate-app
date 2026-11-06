package com.hassaku.plamotranslate.data.inference

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.nehuatl.llamacpp.LlamaHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlamoLlamaEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TranslationEngine {

    companion object {
        private const val TAG = "PlamoLlamaEngine"
        private const val DEFAULT_CTX = 2048
        private const val GEN_TIMEOUT_MS = 180_000L
        private const val LOAD_TIMEOUT_MS = 300_000L
        const val MAX_INPUT_CHARS = 4000
        private const val MIN_MODEL_BYTES = 300_000_000L
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var helper: LlamaHelper? = null
    @Volatile private var modelLoaded = false
    private var currentModelPath: String? = null
    private val loading = AtomicBoolean(false)
    private val translating = AtomicBoolean(false)
    private val requestId = AtomicLong(0)

    override val isReady: Boolean
        get() = modelLoaded && helper != null

    override val engineName: String
        get() = if (isReady) "PLaMo-3-0.8B (GGUF)" else "PLaMo (not loaded)"

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

                // LlamaHelper opens the model ONLY via ContentResolver.openFileDescriptor.
                // file:// URIs fail on modern Android — must use FileProvider content://
                val contentUri = try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "FileProvider failed for ${file.absolutePath}", e)
                    return@withContext Result.failure(
                        IllegalStateException("モデルURIの生成に失敗: ${e.message}")
                    )
                }

                // Verify ContentResolver can actually open it before native load
                try {
                    context.contentResolver.openFileDescriptor(contentUri, "r").use { pfd ->
                        if (pfd == null) {
                            return@withContext Result.failure(
                                IllegalStateException("モデルを開けません (openFileDescriptor=null)")
                            )
                        }
                        Log.i(TAG, "FD ok, size=${pfd.statSize}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open content URI", e)
                    return@withContext Result.failure(
                        IllegalStateException("モデルを開けません: ${e.message}")
                    )
                }

                Log.i(
                    TAG,
                    "Loading ${file.length() / 1_000_000}MB uri=$contentUri path=${file.absolutePath}"
                )

                requestId.incrementAndGet()
                runCatching { helper?.release() }
                helper = null
                modelLoaded = false

                val h = LlamaHelper(
                    contentResolver = context.contentResolver,
                    scope = scope,
                    sharedFlow = eventFlow
                )
                val loaded = CompletableDeferred<Unit>()
                val myLoadGen = requestId.get()

                val collectJob = scope.launch {
                    eventFlow.collect { ev ->
                        if (requestId.get() != myLoadGen) return@collect
                        when (ev) {
                            is LlamaHelper.LLMEvent.Error -> {
                                Log.e(TAG, "Load event error: ${ev.message}")
                                if (!loaded.isCompleted) {
                                    loaded.completeExceptionally(RuntimeException(ev.message))
                                }
                            }
                            is LlamaHelper.LLMEvent.Loaded -> {
                                if (!loaded.isCompleted) loaded.complete(Unit)
                            }
                            else -> Unit
                        }
                    }
                }

                try {
                    h.load(path = contentUri.toString(), contextLength = DEFAULT_CTX) { id ->
                        Log.i(TAG, "load callback contextId=$id")
                        if (!loaded.isCompleted && requestId.get() == myLoadGen) {
                            loaded.complete(Unit)
                        }
                    }
                    withTimeout(LOAD_TIMEOUT_MS) { loaded.await() }
                } finally {
                    collectJob.cancel()
                }

                helper = h
                modelLoaded = true
                currentModelPath = modelPath
                Log.i(TAG, "Model loaded OK")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadModel failed", e)
            runCatching { helper?.release() }
            helper = null
            modelLoaded = false
            val root = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            val msg = when {
                e.message?.contains("OutOfMemory", ignoreCase = true) == true ||
                    e is OutOfMemoryError ->
                    "メモリ不足でロードできません。他アプリを閉じて再試行"
                e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("Timed out", ignoreCase = true) == true ->
                    "ロードがタイムアウトしました（端末が重い／メモリ不足の可能性）"
                e.message?.contains("Cannot open", ignoreCase = true) == true ||
                    e.message?.contains("Failed to open", ignoreCase = true) == true ->
                    "モデルファイルを開けませんでした。再ダウンロードを試してください"
                e.message?.contains("initialization failed", ignoreCase = true) == true ->
                    "ネイティブ初期化に失敗。別の量子化モデルを試すか再DLしてください"
                else -> root.ifBlank { "モデルの読み込みに失敗しました" }
            }
            Result.failure(RuntimeException(msg, e))
        } finally {
            loading.set(false)
        }
    }

    override fun unloadModel() {
        requestId.incrementAndGet()
        try { helper?.release() } catch (e: Exception) { Log.w(TAG, "unload error", e) }
        finally {
            helper = null
            modelLoaded = false
            currentModelPath = null
        }
    }

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        style: Style
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isReady) return@withContext Result.failure(IllegalStateException("モデルがロードされていません"))
        if (!translating.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("翻訳処理中です"))
        }
        val clipped = if (text.length > MAX_INPUT_CHARS) text.take(MAX_INPUT_CHARS) else text
        val h = helper
        if (h == null) {
            translating.set(false)
            return@withContext Result.failure(IllegalStateException("エンジンが初期化されていません"))
        }
        val myId = requestId.incrementAndGet()
        try {
            mutex.withLock {
                try {
                    val prompt = buildPrompt(clipped, sourceLang, targetLang, style)
                    val done = CompletableDeferred<String>()
                    val collectJob = scope.launch {
                        eventFlow.collect { ev ->
                            if (requestId.get() != myId) return@collect
                            when (ev) {
                                is LlamaHelper.LLMEvent.Done -> {
                                    if (!done.isCompleted) done.complete(ev.fullText)
                                }
                                is LlamaHelper.LLMEvent.Error -> {
                                    if (!done.isCompleted) {
                                        done.completeExceptionally(RuntimeException(ev.message))
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                    try {
                        h.predict(prompt = prompt, partialCompletion = true)
                        val raw = withTimeout(GEN_TIMEOUT_MS) { done.await() }
                        Result.success(postProcess(raw))
                    } finally {
                        collectJob.cancel()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "translate failed", e)
                    Result.failure(e)
                }
            }
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
