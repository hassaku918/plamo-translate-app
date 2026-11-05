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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class TranslateUiState(
    val input: String = "",
    val output: String = "",
    val sourceLang: String = "English",
    val targetLang: String = "Japanese",
    val style: Style = Style.PLAIN,
    val isTranslating: Boolean = false,
    val isModelReady: Boolean = false,
    val engineName: String = "Mock",
    val error: String? = null,
    val downloadProgress: Float = 0f,
    val modelStatus: ModelStatus = ModelStatus.NotDownloaded
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
    private val autoLoadStarted = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            modelManager.status.collect { status ->
                _uiState.update { it.copy(modelStatus = status) }
                if (status is ModelStatus.Ready &&
                    !realEngine.isReady &&
                    autoLoadStarted.compareAndSet(false, true)
                ) {
                    loadModel(status.path)
                }
            }
        }
        viewModelScope.launch {
            modelManager.downloadProgress.collect { p ->
                _uiState.update { it.copy(downloadProgress = p) }
            }
        }
        if (modelManager.isModelReady() && !realEngine.isReady) {
            if (autoLoadStarted.compareAndSet(false, true)) {
                loadModel(modelManager.modelFile.absolutePath)
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
            _uiState.update { it.copy(isTranslating = true, error = null, output = "") }
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
                            engineName = activeEngine.engineName
                        )
                    }
                },
                onFailure = { e ->
                    val msg = when {
                        e.message?.contains("already in progress") == true ->
                            "翻訳処理中です。完了までお待ちください"
                        else -> e.message ?: "翻訳に失敗しました"
                    }
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            error = msg,
                            engineName = activeEngine.engineName
                        )
                    }
                }
            )
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            autoLoadStarted.set(false)
            modelManager.downloadModel()
        }
    }

    fun loadModel(path: String? = null) {
        viewModelScope.launch {
            val targetPath = path
                ?: (modelManager.status.value as? ModelStatus.Ready)?.path
                ?: modelManager.modelFile.absolutePath

            _uiState.update { it.copy(error = null) }
            val result = realEngine.loadModel(targetPath)
            result.fold(
                onSuccess = {
                    activeEngine = realEngine
                    refreshEngineStatus()
                },
                onFailure = { e ->
                    activeEngine = mockEngine
                    autoLoadStarted.set(false)
                    _uiState.update {
                        it.copy(error = "モデルの読み込みに失敗しました")
                    }
                    refreshEngineStatus()
                }
            )
        }
    }

    private fun refreshEngineStatus() {
        _uiState.update {
            it.copy(
                isModelReady = realEngine.isReady,
                engineName = activeEngine.engineName
            )
        }
    }
}
