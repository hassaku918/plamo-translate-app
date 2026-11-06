package com.hassaku.plamotranslate.data.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
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

/**
 * Offline OCR via ML Kit Japanese text recognition.
 * Latin/English mixed text is also handled reasonably by the Japanese model.
 */
@Singleton
class TextRecognitionHelper @Inject constructor() {

    private val recognizer: TextRecognizer = TextRecognition.getClient(
        JapaneseTextRecognizerOptions.Builder().build()
    )

    suspend fun recognize(bitmap: Bitmap): RecognitionResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        return process(recognizer, image)
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
        recognizer.close()
    }
}
