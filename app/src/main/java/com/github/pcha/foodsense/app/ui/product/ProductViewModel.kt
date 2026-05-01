package com.github.pcha.foodsense.app.ui.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.github.pcha.foodsense.app.data.Item
import com.github.pcha.foodsense.app.data.Product
import com.github.pcha.foodsense.app.data.ProductRepository
import com.github.pcha.foodsense.app.data.local.database.ProductUnit
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ProductViewModel @Inject constructor(
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductUiState())
    val uiState: StateFlow<ProductUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            productRepository.products
                .catch { e -> _uiState.update { it.copy(error = e, isLoading = false) } }
                .collect { items -> _uiState.update { it.copy(products = items, isLoading = false) } }
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
        )
    }

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
}

data class ProductUiState(
    val products: List<Product> = emptyList(),
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
)
