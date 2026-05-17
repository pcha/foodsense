package com.github.pcha.foodsense.app.ui.scan

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.pcha.foodsense.app.data.barcode.BarcodeProduct
import com.github.pcha.foodsense.app.data.barcode.BarcodeRepository
import com.github.pcha.foodsense.app.data.barcode.BarcodeResult
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

enum class ScanPhase { Barcode, Ocr }

data class PendingBarcodeResult(
    val barcode: String,
    val product: BarcodeProduct,
    val fromCache: Boolean,
)

data class ScanUiState(
    val phase: ScanPhase = ScanPhase.Barcode,
    val isProcessing: Boolean = false,
    val error: String? = null,
    val pendingResult: PendingBarcodeResult? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val barcodeRepository: BarcodeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun switchToOcr() = _uiState.update { it.copy(phase = ScanPhase.Ocr, error = null) }

    fun lookupBarcode(barcode: String, onNotFound: () -> Unit) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(isProcessing = true, error = null) }
        viewModelScope.launch {
            val result = barcodeRepository.lookup(barcode)
            if (result != null) {
                Log.d(TAG, "${if (result.fromCache) "cache" else "api"} hit '$barcode': ${result.product.name}")
                _uiState.update { it.copy(isProcessing = false, pendingResult = result.toPending(barcode)) }
            } else {
                Log.d(TAG, "not found '$barcode'")
                _uiState.update { it.copy(isProcessing = false) }
                onNotFound()
            }
        }
    }

    fun confirmPendingResult(onConfirmed: (barcode: String, BarcodeProduct) -> Unit) {
        val pending = _uiState.value.pendingResult ?: return
        viewModelScope.launch {
            if (!pending.fromCache) {
                barcodeRepository.save(pending.barcode, pending.product)
                Log.d(TAG, "saved '${pending.barcode}' to registry")
            }
            _uiState.update { it.copy(pendingResult = null) }
            onConfirmed(pending.barcode, pending.product)
        }
    }

    fun rejectPendingResult(onRejected: (String) -> Unit) {
        val pending = _uiState.value.pendingResult ?: return
        viewModelScope.launch {
            if (pending.fromCache) {
                barcodeRepository.delete(pending.barcode)
                Log.d(TAG, "deleted '${pending.barcode}' from registry")
            }
            _uiState.update { it.copy(pendingResult = null) }
            onRejected(pending.barcode)
        }
    }

    fun processImage(bitmap: Bitmap, onOcrResult: (ProductOcrResult) -> Unit) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(isProcessing = true, error = null) }
        viewModelScope.launch {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)

                val mlkitCode = detectBarcodeCode(image)
                Log.d(TAG, "ML Kit barcode code: $mlkitCode")
                if (mlkitCode != null) {
                    val result = barcodeRepository.lookup(mlkitCode)
                    if (result != null) {
                        _uiState.update { it.copy(isProcessing = false, pendingResult = result.toPending(mlkitCode)) }
                        return@launch
                    }
                }

                val visionText = recognizer.process(image).await()
                val lines = visionText.textBlocks.flatMap { it.lines }.map { it.text }
                Log.d(TAG, "ocr lines: $lines")

                val ocrCode = extractBarcodeNumber(lines)
                Log.d(TAG, "ocr barcode number: $ocrCode")
                if (ocrCode != null) {
                    val result = barcodeRepository.lookup(ocrCode)
                    if (result != null) {
                        _uiState.update { it.copy(isProcessing = false, pendingResult = result.toPending(ocrCode)) }
                        return@launch
                    }
                }

                val ocrResult = extractProductInfo(lines)
                if (ocrResult.productName != null) {
                    _uiState.update { it.copy(isProcessing = false) }
                    onOcrResult(ocrResult)
                    return@launch
                }

                _uiState.update { it.copy(isProcessing = false, error = "No se encontró ningún producto") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.message) }
            }
        }
    }

    private fun BarcodeResult.toPending(barcode: String) =
        PendingBarcodeResult(barcode, product, fromCache)

    private companion object {
        const val TAG = "FoodSense"
    }

    private suspend fun detectBarcodeCode(image: InputImage): String? {
        val scanner = BarcodeScanning.getClient()
        return try {
            scanner.process(image).await()
                .firstOrNull { it.format != Barcode.FORMAT_UNKNOWN && it.rawValue != null }
                ?.rawValue
        } catch (_: Exception) {
            null
        } finally {
            scanner.close()
        }
    }

    override fun onCleared() {
        super.onCleared()
        recognizer.close()
    }
}
