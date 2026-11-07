package com.hassaku.plamotranslate.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hassaku.plamotranslate.data.inference.MockTranslationEngine
import com.hassaku.plamotranslate.data.inference.PlamoLlamaEngine
import com.hassaku.plamotranslate.data.inference.Style
import com.hassaku.plamotranslate.data.inference.TranslationEngine
import com.hassaku.plamotranslate.data.model.ModelManager
import com.hassaku.plamotranslate.data.model.ModelStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranslateUiState(
    val input: String = "",
    val output: String = "",
    val sourceLang: String = "English",
    val targetLang: String = "Japanese",
    val style: Style = Style.PLAIN,
    val isTranslating: Boolean = false,
    val isModelReady: Boolean = false,
    val isLoadingModel: Boolean = false,
    val engineName: String = "Mock",
    val error: String? = null,
    val downloadProgress: Float = 0f,
    val modelStatus: ModelStatus = ModelStatus.NotDownloaded,
    val isModelInMemory: Boolean = false,
    /** User-facing status while waiting (load / infer). */
    val statusText: String? = null
)

@HiltViewModel
class TranslateViewModel @Inject constructor(
    private val realEngine: PlamoLlamaEngine,
    private val mockEngine: MockTranslationEngine,
    private val modelManager: ModelManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranslateUiState())
    val uiState: StateFlow<TranslateUiState> = _uiState.asStateFlow()

    private var activeEngine: TranslationEngine = mockEngine
    private var idleUnloadJob: Job? = null

    companion object {
        // Keep warm while actively using the app — reloading is the slow part.
        private const val IDLE_UNLOAD_MS = 120_000L
    }

    init {
        viewModelScope.launch {
            modelManager.status.collect { status ->
                _uiState.update { it.copy(modelStatus = status) }
            }
        }
        viewModelScope.launch {
            modelManager.downloadProgress.collect { p ->
                _uiState.update { it.copy(downloadProgress = p) }
            }
        }
        refreshEngineStatus()
    }

    fun onInputChange(text: String) {
        val clipped = if (text.length > PlamoLlamaEngine.MAX_INPUT_CHARS)
            text.take(PlamoLlamaEngine.MAX_INPUT_CHARS) else text
        _uiState.update { it.copy(input = clipped, error = null) }
    }

    fun setInputText(text: String) {
        val clipped = if (text.length > PlamoLlamaEngine.MAX_INPUT_CHARS)
            text.take(PlamoLlamaEngine.MAX_INPUT_CHARS) else text
        _uiState.update { it.copy(input = clipped, error = null, output = "") }
    }

    fun setStyle(style: Style) {
        _uiState.update { it.copy(style = style) }
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(
                sourceLang = it.targetLang,
                targetLang = it.sourceLang,
                input = it.output,
                output = it.input
            )
        }
    }

    fun translate() {
        val state = _uiState.value
        if (state.input.isBlank() || state.isTranslating) return

        viewModelScope.launch {
            idleUnloadJob?.cancel()
            _uiState.update {
                it.copy(
                    isTranslating = true,
                    error = null,
                    output = "",
                    statusText = "準備中…"
                )
            }

            if (!realEngine.isReady && modelManager.isModelReady()) {
                _uiState.update {
                    it.copy(
                        isLoadingModel = true,
                        modelStatus = ModelStatus.Loading,
                        statusText = "モデル読み込み中（初回のみ遅い）…"
                    )
                }
                val loadResult = realEngine.loadModel(modelManager.modelFile.absolutePath)
                _uiState.update { it.copy(isLoadingModel = false) }
                if (loadResult.isFailure) {
                    activeEngine = mockEngine
                    modelManager.refreshStatus()
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            error = loadResult.exceptionOrNull()?.message
                                ?: "モデルの読み込みに失敗しました",
                            isModelInMemory = false,
                            statusText = null
                        )
                    }
                    refreshEngineStatus()
                    return@launch
                }
                activeEngine = realEngine
                modelManager.refreshStatus()
                refreshEngineStatus()
            }

            // Prefer real engine if loaded
            if (realEngine.isReady) activeEngine = realEngine

            _uiState.update { it.copy(statusText = "CPUで推論中…（短文推奨）") }

            val result = activeEngine.translate(
                text = state.input,
                sourceLang = state.sourceLang,
                targetLang = state.targetLang,
                style = state.style
            )
            result.fold(
                onSuccess = { out ->
                    _uiState.update {
                        it.copy(
                            output = out,
                            isTranslating = false,
                            engineName = activeEngine.engineName,
                            isModelInMemory = realEngine.isReady,
                            statusText = null
                        )
                    }
                    if (realEngine.isReady) scheduleIdleUnload()
                },
                onFailure = { e ->
                    val msg = when {
                        e.message?.contains("already in progress") == true ||
                            e.message?.contains("処理中") == true ->
                            "翻訳処理中です。完了までお待ちください"
                        else -> e.message ?: "翻訳に失敗しました"
                    }
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            error = msg,
                            engineName = activeEngine.engineName,
                            isModelInMemory = realEngine.isReady,
                            statusText = null
                        )
                    }
                    if (realEngine.isReady) scheduleIdleUnload()
                }
            )
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            val result = modelManager.downloadModel()
            result.onFailure { e ->
                _uiState.update {
                    it.copy(error = e.message ?: "ダウンロードに失敗しました")
                }
            }
        }
    }

    fun loadModel(path: String? = null) {
        viewModelScope.launch {
            idleUnloadJob?.cancel()
            val targetPath = path
                ?: (modelManager.status.value as? ModelStatus.Ready)?.path
                ?: modelManager.modelFile.absolutePath

            _uiState.update {
                it.copy(
                    error = null,
                    isLoadingModel = true,
                    modelStatus = ModelStatus.Loading,
                    statusText = "モデル読み込み中…"
                )
            }
            val result = realEngine.loadModel(targetPath)
            result.fold(
                onSuccess = {
                    activeEngine = realEngine
                    modelManager.refreshStatus()
                    _uiState.update {
                        it.copy(
                            isLoadingModel = false,
                            isModelInMemory = true,
                            statusText = null
                        )
                    }
                    refreshEngineStatus()
                    scheduleIdleUnload()
                },
                onFailure = { e ->
                    activeEngine = mockEngine
                    modelManager.refreshStatus()
                    _uiState.update {
                        it.copy(
                            isLoadingModel = false,
                            error = e.message ?: "モデルの読み込みに失敗しました",
                            isModelInMemory = false,
                            statusText = null
                        )
                    }
                    refreshEngineStatus()
                }
            )
        }
    }

    fun unloadModel() {
        idleUnloadJob?.cancel()
        realEngine.unloadModel()
        activeEngine = mockEngine
        modelManager.refreshStatus()
        _uiState.update {
            it.copy(
                isModelInMemory = false,
                engineName = mockEngine.engineName,
                isModelReady = false,
                statusText = null
            )
        }
        refreshEngineStatus()
    }

    fun deleteModel() {
        unloadModel()
        modelManager.deleteModel()
        _uiState.update { it.copy(error = null) }
    }

    fun onAppBackgrounded() {
        if (realEngine.isReady) {
            unloadModel()
        }
    }

    private fun scheduleIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = viewModelScope.launch {
            delay(IDLE_UNLOAD_MS)
            if (realEngine.isReady) {
                unloadModel()
            }
        }
    }

    private fun refreshEngineStatus() {
        _uiState.update {
            it.copy(
                isModelReady = realEngine.isReady,
                isModelInMemory = realEngine.isReady,
                engineName = activeEngine.engineName
            )
        }
    }

    override fun onCleared() {
        idleUnloadJob?.cancel()
        realEngine.unloadModel()
        super.onCleared()
    }
}
