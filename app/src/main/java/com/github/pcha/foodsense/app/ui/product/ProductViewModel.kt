package com.github.pcha.foodsense.app.ui.product

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.pcha.foodsense.app.ui.scan.extractDateOnly
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.github.pcha.foodsense.app.data.Item
import com.github.pcha.foodsense.app.data.Product
import com.github.pcha.foodsense.app.data.ProductRepository
import com.github.pcha.foodsense.app.data.barcode.BarcodeProduct
import com.github.pcha.foodsense.app.data.barcode.BarcodeRepository
import com.github.pcha.foodsense.app.data.local.database.ProductUnit
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ProductViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val barcodeRepository: BarcodeRepository,
) : ViewModel() {

    private val _recognizer = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer by _recognizer

    private val _uiState = MutableStateFlow(ProductUiState())
    val uiState: StateFlow<ProductUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            productRepository.products
                .catch { e -> _uiState.update { it.copy(error = e, isLoading = false) } }
                .collect { items -> _uiState.update { it.copy(products = items, isLoading = false) } }
        }
        viewModelScope.launch {
            productRepository.productNames
                .collect { names -> _uiState.update { it.copy(allProductNames = names) } }
        }
    }

    fun openAddSheet() = _uiState.update { it.copy(showAddSheet = true) }

    fun openEditSheet(product: Product) = _uiState.update {
        it.copy(
            showAddSheet = true,
            editingProductId = product.uid,
            formName = product.name,
        )
    }

    fun openQuickAddSheet(product: Product) = _uiState.update {
        it.copy(
            showAddSheet = true,
            addingToProductId = product.uid,
            formName = product.name,
        )
    }

    fun openEditItemSheet(groupItems: List<Item>) {
        val first = groupItems.first()
        val qtyStr = first.quantity.let {
            if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString()
        }
        _uiState.update {
            it.copy(
                showAddSheet = true,
                editingGroupItemIds = groupItems.map { item -> item.uid },
                maxApplyCount = groupItems.size,
                formApplyCount = "1",
                formQuantity = qtyStr,
                formUnit = first.unit,
                formExpirationDate = first.expirationDate,
            )
        }
    }

    fun dismissAddSheet() = _uiState.update {
        it.copy(
            showAddSheet = false,
            editingProductId = null,
            addingToProductId = null,
            editingGroupItemIds = emptyList(),
            maxApplyCount = 1,
            formName = "",
            formQuantity = "1",
            formUnit = null,
            formBatchCount = "1",
            formApplyCount = "1",
            formExpirationDate = null,
            pendingBarcode = null,
            originalFormName = null,
            rememberBarcode = true,
        )
    }

    fun openAddSheetForManualEntry(barcode: String) = _uiState.update {
        it.copy(showAddSheet = true, pendingBarcode = barcode, rememberBarcode = true)
    }

    fun setRememberBarcode(value: Boolean) = _uiState.update { it.copy(rememberBarcode = value) }

    fun onFormNameChange(value: String) = _uiState.update { it.copy(formName = value) }

    fun onFormQuantityChange(value: String) {
        val dotCount = value.count { it == '.' }
        if (value.all { it.isDigit() || it == '.' } && dotCount <= 1) {
            _uiState.update { it.copy(formQuantity = value) }
        }
    }

    fun onFormUnitChange(unit: ProductUnit?) = _uiState.update { it.copy(formUnit = unit) }

    fun onFormBatchCountChange(value: String) {
        if (value.all { it.isDigit() }) _uiState.update { it.copy(formBatchCount = value) }
    }

    fun onFormApplyCountChange(value: String) {
        val max = _uiState.value.maxApplyCount
        if (value.all { it.isDigit() }) {
            val n = value.toIntOrNull()
            if (n == null || (n in 1..max)) _uiState.update { it.copy(formApplyCount = value) }
        }
    }

    fun onFormDateSelected(date: LocalDate) = _uiState.update { it.copy(formExpirationDate = date) }

    fun addProduct() {
        val state = _uiState.value
        viewModelScope.launch {
            when {
                state.editingGroupItemIds.isNotEmpty() -> {
                    val n = state.formApplyCount.toIntOrNull()?.coerceIn(1, state.maxApplyCount) ?: 1
                    val qty = state.formQuantity.toFloatOrNull()?.takeIf { it > 0f } ?: return@launch
                    state.editingGroupItemIds.take(n).forEach { id ->
                        productRepository.updateItem(id, qty, state.formUnit, state.formExpirationDate)
                    }
                }
                state.editingProductId != null -> {
                    productRepository.updateProduct(state.editingProductId, state.formName.trim())
                }
                else -> {
                    val qty = state.formQuantity.toFloatOrNull()?.takeIf { it > 0f } ?: return@launch
                    val batchCount = state.formBatchCount.toIntOrNull()?.takeIf { it > 0 } ?: 1
                    if (state.pendingBarcode != null && state.rememberBarcode) {
                        val quantityStr = buildString {
                            append(state.formQuantity)
                            if (state.formUnit != null) append(" ${state.formUnit.name}")
                        }.trim()
                        barcodeRepository.save(
                            state.pendingBarcode,
                            BarcodeProduct(state.formName.trim(), quantityStr.ifEmpty { null }),
                        )
                    }
                    repeat(batchCount) {
                        productRepository.add(state.formName.trim(), qty, state.formUnit, state.formExpirationDate)
                    }
                }
            }
            dismissAddSheet()
        }
    }

    fun deleteItem(itemId: Int) {
        viewModelScope.launch {
            productRepository.deleteItem(itemId)
        }
    }

    fun deleteItems(itemIds: List<Int>) {
        viewModelScope.launch {
            itemIds.forEach { productRepository.deleteItem(it) }
        }
    }

    fun openAddSheetWithScanResult(
        barcode: String?,
        name: String?,
        quantityStr: String?,
        unit: String?,
        dateStr: String?,
    ) {
        val parsedQty = parseQuantity(quantityStr)
        val parsedUnit = parseUnit(unit)
        val parsedDate = parseDateStr(dateStr)
        val qtyStr = parsedQty?.let {
            if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString()
        } ?: "1"
        val trimmedName = name?.trim() ?: ""
        _uiState.update {
            it.copy(
                showAddSheet = true,
                pendingBarcode = barcode,
                originalFormName = if (barcode != null) trimmedName else null,
                rememberBarcode = true,
                formName = trimmedName,
                formQuantity = qtyStr,
                formUnit = parsedUnit,
                formExpirationDate = parsedDate,
            )
        }
    }

    private fun parseQuantity(str: String?): Float? =
        str?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0f }

    private fun parseUnit(str: String?): ProductUnit? = when (str?.uppercase()) {
        "L" -> ProductUnit.L
        "ML", "CC", "CL" -> ProductUnit.ML
        "G", "GR" -> ProductUnit.G
        "KG" -> ProductUnit.KG
        else -> null
    }

    fun openDateScanner() = _uiState.update { it.copy(showDateScanner = true, dateScanError = false) }

    fun dismissDateScanner() = _uiState.update { it.copy(showDateScanner = false, dateScanError = false) }

    fun processCapturedDateImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.update { it.copy(dateScanError = false) }
            try {
                val lines = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                    .textBlocks.flatMap { it.lines }.map { it.text }
                val date = parseDateStr(extractDateOnly(lines))
                if (date != null) {
                    _uiState.update { it.copy(formExpirationDate = date, showDateScanner = false, dateScanError = false) }
                } else {
                    _uiState.update { it.copy(dateScanError = true) }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(dateScanError = true) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (_recognizer.isInitialized()) recognizer.close()
    }

    private fun parseDateStr(str: String?): LocalDate? {
        if (str == null) return null
        val locale = Locale.getDefault()

        val cleaned = str.trim().replace(SEPARATOR_RE, " ")
        val locales = buildList {
            add(locale)
            if (locale != Locale.ENGLISH) add(Locale.ENGLISH)
            if (locale.language != "es") add(Locale("es"))
        }
        for (monthLocale in locales) {
            for (pattern in listOf("d MMM yyyy", "d MMMM yyyy")) {
                try {
                    return DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                        .toFormatter(monthLocale).let { LocalDate.parse(cleaned, it) }
                } catch (_: DateTimeParseException) {}
            }
            for (pattern in listOf("MMM yyyy", "MMMM yyyy")) {
                try {
                    return DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                        .toFormatter(monthLocale).let { YearMonth.parse(cleaned, it).atEndOfMonth() }
                } catch (_: DateTimeParseException) {}
            }
        }

        val normalized = str.trim().replace(NUMERIC_SEPARATOR_RE, "/")
        val patterns = if (locale.language == "en" && locale.country == "US") {
            listOf("M/d/yyyy", "M/d/yy", "d/M/yyyy", "d/M/yy")
        } else {
            listOf("d/M/yyyy", "d/M/yy")
        }
        for (pattern in patterns) {
            try {
                return LocalDate.parse(normalized, DateTimeFormatter.ofPattern(pattern))
            } catch (_: DateTimeParseException) {}
        }

        try {
            return YearMonth.parse(normalized, DateTimeFormatter.ofPattern("M/yyyy")).atEndOfMonth()
        } catch (_: DateTimeParseException) {}
        return null
    }

    private companion object {
        val SEPARATOR_RE = Regex("""[.\-/]""")
        val NUMERIC_SEPARATOR_RE = Regex("""[.\-]""")
    }
}

data class ProductUiState(
    val products: List<Product> = emptyList(),
    val allProductNames: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val showAddSheet: Boolean = false,
    val editingProductId: Int? = null,
    val addingToProductId: Int? = null,
    val editingGroupItemIds: List<Int> = emptyList(),
    val maxApplyCount: Int = 1,
    val formName: String = "",
    val formQuantity: String = "1",
    val formUnit: ProductUnit? = null,
    val formBatchCount: String = "1",
    val formApplyCount: String = "1",
    val formExpirationDate: LocalDate? = null,
    val pendingBarcode: String? = null,
    val originalFormName: String? = null,
    val rememberBarcode: Boolean = true,
    val showDateScanner: Boolean = false,
    val dateScanError: Boolean = false,
)
