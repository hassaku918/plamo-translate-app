package com.hassaku.plamotranslate.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RecognizedBlock(
    val text: String,
    val boundingBox: Rect?,
    val confidence: Float? = null,
    val lines: List<String> = emptyList()
)

data class RecognitionResult(
    val fullText: String,
    val blocks: List<RecognizedBlock>
)

@Singleton
class TextRecognitionHelper @Inject constructor() {

    private val japaneseRecognizer: TextRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    private val latinRecognizer: TextRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    suspend fun recognize(bitmap: Bitmap): RecognitionResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val japaneseResult = runCatching { process(japaneseRecognizer, image) }.getOrNull()
        if (japaneseResult != null && japaneseResult.fullText.trim().length >= 2) {
            return japaneseResult
        }
        return process(latinRecognizer, image)
    }

    private suspend fun process(
        client: TextRecognizer,
        image: InputImage
    ): RecognitionResult = suspendCancellableCoroutine { cont ->
        client.process(image)
            .addOnSuccessListener { visionText ->
                cont.resume(visionText.toResult())
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    private fun Text.toResult(): RecognitionResult {
        val blocks = textBlocks.map { block ->
            RecognizedBlock(
                text = block.text,
                boundingBox = block.boundingBox,
                confidence = null,
                lines = block.lines.map { it.text }
            )
        }
        return RecognitionResult(fullText = text, blocks = blocks)
    }

    fun close() {
        japaneseRecognizer.close()
        latinRecognizer.close()
    }
}
