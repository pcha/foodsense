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
import com.github.pcha.foodsense.app.data.ProductItem
import com.github.pcha.foodsense.app.data.ProductRepository
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

    fun openEditSheet(product: ProductItem) = _uiState.update {
        it.copy(
            showAddSheet = true,
            editingProduct = product,
            formName = product.name,
            formQuantity = product.quantity.toString(),
            formExpirationDate = product.expirationDate,
        )
    }

    fun dismissAddSheet() = _uiState.update {
        it.copy(
            showAddSheet = false,
            editingProduct = null,
            formName = "",
            formQuantity = "1",
            formExpirationDate = null,
        )
    }

    fun onFormNameChange(value: String) = _uiState.update { it.copy(formName = value) }

    fun onFormQuantityChange(value: String) {
        if (value.all { it.isDigit() }) _uiState.update { it.copy(formQuantity = value) }
    }

    fun onFormDateSelected(date: LocalDate) = _uiState.update { it.copy(formExpirationDate = date) }

    fun addProduct() {
        val state = _uiState.value
        val date = state.formExpirationDate ?: return
        val qty = state.formQuantity.toIntOrNull()?.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            val editing = state.editingProduct
            if (editing != null) {
                productRepository.update(editing.uid, state.formName.trim(), qty, date)
            } else {
                productRepository.add(state.formName.trim(), qty, date)
            }
            dismissAddSheet()
        }
    }

    fun deleteProduct(uid: Int) {
        viewModelScope.launch {
            productRepository.delete(uid)
        }
    }
}

data class ProductUiState(
    val products: List<ProductItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: Throwable? = null,
    val showAddSheet: Boolean = false,
    val editingProduct: ProductItem? = null,
    val formName: String = "",
    val formQuantity: String = "1",
    val formExpirationDate: LocalDate? = null,
)
