package com.hassaku.llamajni

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thin JNI façade over a current llama.cpp build (compiled in :llamajni).
 * Supports plamo3 when the vendored llama.cpp tree includes that architecture.
 */
object NativeLlama {
    private val mutex = Mutex()

    init {
        System.loadLibrary("llamajni")
        nativeInit()
    }

    private external fun nativeInit(): Boolean
    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): String?
    private external fun nativeUnload()
    private external fun nativeComplete(prompt: String, maxTokens: Int): String
    private external fun nativeIsLoaded(): Boolean

    val isLoaded: Boolean get() = nativeIsLoaded()

    suspend fun load(path: String, nCtx: Int = 1024, nThreads: Int = 4): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val err = nativeLoad(path, nCtx, nThreads)
                if (err == null) Result.success(Unit)
                else Result.failure(RuntimeException(err))
            }
        }

    suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock { nativeUnload() }
    }

    suspend fun complete(prompt: String, maxTokens: Int = 512): Result<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!nativeIsLoaded()) {
                    Result.failure(IllegalStateException("model not loaded"))
                } else {
                    Result.success(nativeComplete(prompt, maxTokens))
                }
            }
        }
}
