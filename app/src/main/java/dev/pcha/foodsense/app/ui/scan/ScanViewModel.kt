package dev.pcha.foodsense.app.ui.scan

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pcha.foodsense.app.data.barcode.BarcodeProduct
import dev.pcha.foodsense.app.data.barcode.BarcodeRepository
import dev.pcha.foodsense.app.data.barcode.BarcodeResult
import dev.pcha.foodsense.app.data.mlkit.BarcodeImageScanner
import dev.pcha.foodsense.app.data.mlkit.TextRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val textRecognizer: TextRecognizer,
    private val barcodeScanner: BarcodeImageScanner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun switchToOcr() = _uiState.update { it.copy(phase = ScanPhase.Ocr, error = null) }

    /**
     * Analyzes a live camera frame for a barcode. The caller owns the frame and must release it
     * via [onClose] once analysis completes. Emits detected codes through [onDetected].
     */
    fun analyzeFrame(
        mediaImage: Image,
        rotationDegrees: Int,
        onClose: () -> Unit,
        onDetected: (String) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val code = barcodeScanner.scan(mediaImage, rotationDegrees)
                if (code != null) onDetected(code)
            } finally {
                onClose()
            }
        }
    }

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
                val mlkitCode = barcodeScanner.scan(bitmap)
                Log.d(TAG, "ML Kit barcode code: $mlkitCode")
                if (mlkitCode != null) {
                    val result = barcodeRepository.lookup(mlkitCode)
                    if (result != null) {
                        _uiState.update { it.copy(isProcessing = false, pendingResult = result.toPending(mlkitCode)) }
                        return@launch
                    }
                }

                val lines = textRecognizer.recognizeLines(bitmap)
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
}
