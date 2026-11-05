package com.hassaku.plamotranslate.data.inference

interface TranslationEngine {
    val isReady: Boolean
    val engineName: String

    suspend fun translate(
        text: String,
        sourceLang: String = "English",
        targetLang: String = "Japanese",
        style: Style = Style.PLAIN
    ): Result<String>

    suspend fun loadModel(modelPath: String): Result<Unit>
    fun unloadModel()
}

enum class Style {
    PLAIN,
    POLITE
}
