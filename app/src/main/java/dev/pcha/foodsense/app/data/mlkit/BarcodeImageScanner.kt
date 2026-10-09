package dev.pcha.foodsense.app.data.mlkit

import android.graphics.Bitmap
import android.media.Image
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/** On-device barcode detection data source. Wraps ML Kit so the UI/ViewModel never touch the SDK. */
interface BarcodeImageScanner {
    /** Returns the first valid barcode raw value in the bitmap, or null. */
    suspend fun scan(bitmap: Bitmap): String?

    /** Returns the first valid barcode raw value in a camera frame, or null. */
    suspend fun scan(mediaImage: Image, rotationDegrees: Int): String?
}

class MlKitBarcodeScanner @Inject constructor() : BarcodeImageScanner {
    private val client = BarcodeScanning.getClient()

    override suspend fun scan(bitmap: Bitmap): String? =
        detect(InputImage.fromBitmap(bitmap, 0))

    override suspend fun scan(mediaImage: Image, rotationDegrees: Int): String? =
        detect(InputImage.fromMediaImage(mediaImage, rotationDegrees))

    private suspend fun detect(image: InputImage): String? = try {
        client.process(image).await()
            .firstOrNull { it.format != Barcode.FORMAT_UNKNOWN && it.rawValue != null }
            ?.rawValue
    } catch (_: Exception) {
        null
    }
}
