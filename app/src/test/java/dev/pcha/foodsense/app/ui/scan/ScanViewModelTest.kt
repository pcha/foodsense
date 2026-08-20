package dev.pcha.foodsense.app.ui.scan

import android.graphics.Bitmap
import android.media.Image
import dev.pcha.foodsense.app.data.barcode.BarcodeProduct
import dev.pcha.foodsense.app.data.barcode.BarcodeRepository
import dev.pcha.foodsense.app.data.barcode.BarcodeResult
import dev.pcha.foodsense.app.data.mlkit.BarcodeImageScanner
import dev.pcha.foodsense.app.data.mlkit.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repo: BarcodeRepository) =
        ScanViewModel(repo, FakeTextRecognizer(), FakeBarcodeImageScanner())

    @Test
    fun lookupBarcode_found_setsPendingResult() = runTest {
        val repo = FakeBarcodeRepository().apply {
            results["123"] = BarcodeResult(BarcodeProduct("Milk", "1 L"), fromCache = true)
        }
        val vm = viewModel(repo)

        var notFoundCalled = false
        vm.lookupBarcode("123") { notFoundCalled = true }

        assertEquals("Milk", vm.uiState.value.pendingResult?.product?.name)
        assertEquals(false, notFoundCalled)
    }

    @Test
    fun lookupBarcode_notFound_invokesOnNotFound() = runTest {
        val vm = viewModel(FakeBarcodeRepository())

        var notFoundCalled = false
        vm.lookupBarcode("nope") { notFoundCalled = true }

        assertTrue(notFoundCalled)
        assertNull(vm.uiState.value.pendingResult)
    }

    @Test
    fun switchToOcr_setsOcrPhase() = runTest {
        val vm = viewModel(FakeBarcodeRepository())

        vm.switchToOcr()

        assertEquals(ScanPhase.Ocr, vm.uiState.value.phase)
    }

    @Test
    fun confirmPendingResult_notFromCache_savesToRegistry() = runTest {
        val repo = FakeBarcodeRepository().apply {
            results["123"] = BarcodeResult(BarcodeProduct("Eggs", null), fromCache = false)
        }
        val vm = viewModel(repo)
        vm.lookupBarcode("123") {}

        vm.confirmPendingResult { _, _ -> }

        assertEquals(BarcodeProduct("Eggs", null), repo.saved["123"])
        assertNull(vm.uiState.value.pendingResult)
    }

    @Test
    fun rejectPendingResult_fromCache_deletesFromRegistry() = runTest {
        val repo = FakeBarcodeRepository().apply {
            results["123"] = BarcodeResult(BarcodeProduct("Milk", null), fromCache = true)
        }
        val vm = viewModel(repo)
        vm.lookupBarcode("123") {}

        vm.rejectPendingResult { }

        assertTrue("123" in repo.deleted)
        assertNull(vm.uiState.value.pendingResult)
    }
}

private class FakeBarcodeRepository : BarcodeRepository {
    val results = mutableMapOf<String, BarcodeResult>()
    val saved = mutableMapOf<String, BarcodeProduct>()
    val deleted = mutableSetOf<String>()
    override suspend fun lookup(barcode: String): BarcodeResult? = results[barcode]
    override suspend fun save(barcode: String, product: BarcodeProduct) { saved[barcode] = product }
    override suspend fun delete(barcode: String) { deleted += barcode }
}

private class FakeTextRecognizer : TextRecognizer {
    override suspend fun recognizeLines(bitmap: Bitmap): List<String> = emptyList()
}

private class FakeBarcodeImageScanner : BarcodeImageScanner {
    override suspend fun scan(bitmap: Bitmap): String? = null
    override suspend fun scan(mediaImage: Image, rotationDegrees: Int): String? = null
}
