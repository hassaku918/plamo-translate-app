package com.hassaku.plamotranslate.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

/**
 * Offline OCR: Japanese + Latin (alphabet).
 * Runs both models and returns the stronger result (longer non-blank text).
 */
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
        val japanese = runCatching { process(japaneseRecognizer, image) }.getOrNull()
        val latin = runCatching { process(latinRecognizer, image) }.getOrNull()

        val jaScore = score(japanese)
        val laScore = score(latin)

        return when {
            japanese != null && jaScore >= laScore && jaScore > 0 -> japanese
            latin != null && laScore > 0 -> latin
            japanese != null -> japanese
            else -> RecognitionResult(fullText = "", blocks = emptyList())
        }
    }

    private fun score(result: RecognitionResult?): Int {
        if (result == null) return 0
        return result.fullText.trim().length
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
