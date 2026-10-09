package dev.pcha.foodsense.app.data.mlkit

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

interface TextRecognizer {
    suspend fun recognizeLines(bitmap: Bitmap): List<String>
}

class MlKitTextRecognizer @Inject constructor() : TextRecognizer {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeLines(bitmap: Bitmap): List<String> =
        client.process(InputImage.fromBitmap(bitmap, 0)).await()
            .textBlocks.flatMap { it.lines }.map { it.text }
}
