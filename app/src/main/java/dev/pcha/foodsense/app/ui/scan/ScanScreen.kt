package dev.pcha.foodsense.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.pcha.foodsense.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.media.Image
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onResult: (barcode: String?, name: String?, quantity: String?, unit: String?, dateStr: String?) -> Unit,
    onRejected: (barcode: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.camera_permission_required), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.btn_cancel)) }
            }
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (uiState.phase) {
            ScanPhase.Barcode -> BarcodeScannerView(
                isProcessing = uiState.isProcessing,
                onAnalyzeFrame = viewModel::analyzeFrame,
                onBarcodeDetected = { barcode ->
                    viewModel.lookupBarcode(barcode) { viewModel.switchToOcr() }
                },
                onSwitchToOcr = viewModel::switchToOcr,
                onCancel = onCancel,
            )
            ScanPhase.Ocr -> OcrCaptureView(
                isProcessing = uiState.isProcessing,
                onImageCaptured = { bitmap ->
                    viewModel.processImage(bitmap) { result ->
                        val (qty, unit) = splitQuantityAndUnit(result.quantity)
                        onResult(null, result.productName, qty, unit, result.expirationDate)
                    }
                },
                onCancel = onCancel,
            )
        }

        if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
            )
        }
    }

    if (uiState.pendingResult != null) {
        val pending = uiState.pendingResult!!
        ModalBottomSheet(
            onDismissRequest = {},
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.scan_confirm_product_question), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(pending.product.name, style = MaterialTheme.typography.bodyLarge)
                if (pending.product.quantity != null) {
                    Text(pending.product.quantity, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.confirmPendingResult { barcode, product ->
                            val (qty, unit) = splitQuantityAndUnit(product.quantity)
                            onResult(barcode, product.name, qty, unit, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.scan_confirm_yes))
                }
                OutlinedButton(
                    onClick = {
                        viewModel.rejectPendingResult { barcode -> onRejected(barcode) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.scan_confirm_no))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// imageProxy.image es opt-in en CameraX. El marcador está declarado en Java, así que hay que usar
// androidx.annotation.OptIn con markerClass, no el OptIn de Kotlin.
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun BarcodeScannerView(
    isProcessing: Boolean,
    onAnalyzeFrame: (mediaImage: Image, rotationDegrees: Int, onClose: () -> Unit, onDetected: (String) -> Unit) -> Unit,
    onBarcodeDetected: (String) -> Unit,
    onSwitchToOcr: () -> Unit,
    onCancel: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var lastDetectedBarcode by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    onAnalyzeFrame(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees,
                                        { imageProxy.close() },
                                    ) { code ->
                                        if (code != lastDetectedBarcode && !isProcessing) {
                                            lastDetectedBarcode = code
                                            onBarcodeDetected(code)
                                        }
                                    }
                                } else {
                                    imageProxy.close()
                                }
                            }
                        }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isProcessing) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = onSwitchToOcr, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.scan_photograph_label))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    }
}

@Composable
private fun OcrCaptureView(
    isProcessing: Boolean,
    onImageCaptured: (android.graphics.Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isProcessing) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
            } else {
                Button(
                    onClick = {
                        val file = File(context.cacheDir, "ocr_${System.currentTimeMillis()}.jpg")
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                        imageCapture.takePicture(
                            outputOptions,
                            executor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                                    file.delete()
                                    if (bitmap != null) onImageCaptured(bitmap)
                                }
                                override fun onError(exc: ImageCaptureException) {
                                    file.delete()
                                }
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.scan_take_photo))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
    }
}

internal fun splitQuantityAndUnit(raw: String?): Pair<String?, String?> {
    if (raw == null) return Pair(null, null)
    val match = Regex("""(?i)(\d+([.,]\d+)?)\s?(ML|L|G|KG|GR|CC|CL)""").find(raw)
        ?: return Pair(null, null)
    val qty = match.groupValues[1].replace(',', '.')
    val unit = match.groupValues[3].uppercase()
    return Pair(qty, unit)
}
