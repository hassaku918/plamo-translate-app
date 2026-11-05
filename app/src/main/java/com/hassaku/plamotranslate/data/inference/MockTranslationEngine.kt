package com.hassaku.plamotranslate.data.inference

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockTranslationEngine @Inject constructor() : TranslationEngine {

    override val isReady: Boolean = true
    override val engineName: String = "Mock (demo)"

    override suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        style: Style
    ): Result<String> {
        delay(400)
        val t = text.trim()
        if (t.isEmpty()) return Result.success("")
        val result = when {
            t.contains("Hello", ignoreCase = true) || t == "Hello" ->
                if (style == Style.POLITE) "こんにちは。" else "こんにちは"
            t.contains("Thank you", ignoreCase = true) ->
                if (style == Style.POLITE) "ありがとうございます。" else "ありがとう"
            t.contains("How are you", ignoreCase = true) ->
                if (style == Style.POLITE) "お元気ですか？" else "元気？"
            t.length < 30 && sourceLang.equals("Japanese", ignoreCase = true) ->
                "[EN] $t"
            else -> "[${targetLang.take(2).uppercase()}-mock] $t"
        }
        return Result.success(result)
    }

    override suspend fun loadModel(modelPath: String): Result<Unit> = Result.success(Unit)
    override fun unloadModel() {}
}
