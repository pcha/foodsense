package dev.pcha.foodsense.app.data.mlkit

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/** On-device OCR data source. Wraps ML Kit so the UI/ViewModel never touch the SDK. */
interface TextRecognizer {
    /** Recognizes text and returns it split into lines. */
    suspend fun recognizeLines(bitmap: Bitmap): List<String>
}

class MlKitTextRecognizer @Inject constructor() : TextRecognizer {
    // Held for the singleton's (app) lifetime; not created in JVM unit tests, which use a fake.
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeLines(bitmap: Bitmap): List<String> =
        client.process(InputImage.fromBitmap(bitmap, 0)).await()
            .textBlocks.flatMap { it.lines }.map { it.text }
}
