package com.hassaku.plamotranslate.data.model

import android.content.Context
import android.os.StatFs
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelManager"
        const val HF_REPO = "pfnet/plamo-3-translate-0.8b-GGUF"
        // Prefer smaller quants first so mid-range phones can actually load.
        // Official sizes (approx): Q2_K ~410MB, Q3_K_M ~486MB, Q4_0 ~548MB, Q4_K_M ~573MB
        val CANDIDATE_FILES = listOf(
            "plamo-3-translate-0.8b-Q2_K.gguf",
            "plamo-3-translate-0.8b-Q3_K_M.gguf",
            "plamo-3-translate-0.8b-Q4_0.gguf",
            "plamo-3-translate-0.8b-Q4_K_M.gguf"
        )
        private const val MIN_VALID_BYTES = 200_000_000L
        private const val MIN_FREE_BYTES = 600_000_000L
        fun resolveUrl(filename: String) =
            "https://huggingface.co/$HF_REPO/resolve/main/$filename?download=true"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val modelsDir: File
        get() = File(context.filesDir, "models").also { it.mkdirs() }

    /** Canonical on-disk name (any quant is renamed to this). */
    val modelFile: File
        get() = File(modelsDir, "plamo-3-translate.gguf")

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private val downloading = AtomicBoolean(false)

    init { refreshStatus() }

    fun refreshStatus() {
        _status.value = when {
            modelFile.exists() && modelFile.length() >= MIN_VALID_BYTES && hasGgufMagic(modelFile) ->
                ModelStatus.Ready(modelFile.absolutePath)
            modelFile.exists() -> ModelStatus.Corrupted
            else -> ModelStatus.NotDownloaded
        }
    }

    fun isModelReady(): Boolean =
        modelFile.exists() &&
            modelFile.length() >= MIN_VALID_BYTES &&
            hasGgufMagic(modelFile)

    suspend fun downloadModel(): Result<File> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            _status.value = ModelStatus.Ready(modelFile.absolutePath)
            return@withContext Result.success(modelFile)
        }
        if (!downloading.compareAndSet(false, true)) {
            return@withContext Result.failure(IllegalStateException("ダウンロードは既に進行中です"))
        }
        try {
            if (modelFile.exists() && !isModelReady()) {
                modelFile.delete()
            }

            val free = freeSpaceBytes(modelsDir)
            if (free in 1 until MIN_FREE_BYTES) {
                val msg = "ストレージ不足: 空き ${(free / 1_000_000)}MB（目安 600MB以上）"
                _status.value = ModelStatus.Error(msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            _status.value = ModelStatus.Downloading
            _downloadProgress.value = 0f
            var lastError: Exception? = null

            for (filename in CANDIDATE_FILES) {
                val url = resolveUrl(filename)
                Log.i(TAG, "Trying $url")
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "PLaMoTranslateAndroid/1.1")
                        .header("Accept", "application/octet-stream,*/*")
                        .get()
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val err = "HTTP ${response.code} for $filename"
                            Log.w(TAG, err)
                            lastError = IllegalStateException(err)
                            return@use
                        }
                        val body = response.body
                        if (body == null) {
                            lastError = IllegalStateException("空のレスポンス: $filename")
                            return@use
                        }
                        val expected = body.contentLength()
                        val tmp = File(modelsDir, "$filename.tmp")
                        if (tmp.exists()) tmp.delete()

                        FileOutputStream(tmp).use { out ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(256 * 1024)
                                var downloaded = 0L
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    out.write(buffer, 0, read)
                                    downloaded += read
                                    if (expected > 0) {
                                        _downloadProgress.value =
                                            (downloaded.toFloat() / expected).coerceIn(0f, 1f)
                                    } else if (downloaded > 0) {
                                        _downloadProgress.value =
                                            (downloaded.toFloat() / 450_000_000f).coerceIn(0f, 0.99f)
                                    }
                                }
                                out.fd.sync()
                            }
                        }

                        val actual = tmp.length()
                        if (actual < MIN_VALID_BYTES) {
                            tmp.delete()
                            throw IllegalStateException(
                                "ファイルが小さすぎます (${actual / 1_000_000}MB)。ネットワーク切断の可能性"
                            )
                        }
                        if (expected > 0 && kotlin.math.abs(actual - expected) > 1024) {
                            tmp.delete()
                            throw IllegalStateException(
                                "サイズ不一致: expected=$expected actual=$actual"
                            )
                        }
                        if (!hasGgufMagic(tmp)) {
                            tmp.delete()
                            throw IllegalStateException(
                                "GGUF形式ではありません（HTMLが返った可能性）: $filename"
                            )
                        }

                        if (modelFile.exists()) modelFile.delete()
                        if (!tmp.renameTo(modelFile)) {
                            tmp.copyTo(modelFile, overwrite = true)
                            tmp.delete()
                        }

                        Log.i(TAG, "Downloaded ${modelFile.length() / 1_000_000} MB as ${modelFile.name} from $filename")
                        _status.value = ModelStatus.Ready(modelFile.absolutePath)
                        _downloadProgress.value = 1f
                        return@withContext Result.success(modelFile)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed $filename: ${e.message}")
                    lastError = e
                }
            }

            val msg = lastError?.message ?: "すべての候補ファイルのダウンロードに失敗"
            _status.value = ModelStatus.Error(msg)
            Result.failure(lastError ?: Exception(msg))
        } finally {
            downloading.set(false)
        }
    }

    fun deleteModel() {
        modelFile.delete()
        modelsDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") || f.name.endsWith(".gguf")) f.delete()
        }
        refreshStatus()
    }

    private fun freeSpaceBytes(dir: File): Long = try {
        val stat = StatFs(dir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: Exception) { -1L }

    private fun hasGgufMagic(file: File): Boolean = try {
        file.inputStream().use { ins ->
            val magic = ByteArray(4)
            if (ins.read(magic) != 4) return false
            magic[0] == 'G'.code.toByte() &&
                magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) { false }
}

sealed class ModelStatus {
    data object NotDownloaded : ModelStatus()
    data object Downloading : ModelStatus()
    data object Loading : ModelStatus()
    data class Ready(val path: String) : ModelStatus()
    data object Corrupted : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}
