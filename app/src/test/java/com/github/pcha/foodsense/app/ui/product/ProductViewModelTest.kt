package com.github.pcha.foodsense.app.ui.product

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.github.pcha.foodsense.app.data.ProductItem
import com.github.pcha.foodsense.app.data.ProductRepository
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ProductViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_initiallyLoading() = runTest {
        val viewModel = ProductViewModel(FakeProductRepository())
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun uiState_whenRepositoryEmits_isSuccess() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)

        val item = ProductItem(0, "Milk", 2, LocalDate.now())
        repo.emit(listOf(item))

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf(item), viewModel.uiState.value.products)
    }

    @Test
    fun addProduct_validInput_addsToList() = runTest {
        val viewModel = ProductViewModel(FakeProductRepository())
        val date = LocalDate.now().plusDays(5)

        viewModel.onFormNameChange("Bread")
        viewModel.onFormQuantityChange("3")
        viewModel.onFormDateSelected(date)
        viewModel.addProduct()

        assertEquals(1, viewModel.uiState.value.products.size)
        assertEquals("Bread", viewModel.uiState.value.products.first().name)
    }

    @Test
    fun deleteProduct_removesFromList() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val item = ProductItem(0, "Eggs", 12, LocalDate.now())
        repo.emit(listOf(item))

        viewModel.deleteProduct(item.uid)

        assertTrue(viewModel.uiState.value.products.isEmpty())
    }

    @Test
    fun openEditSheet_preloadsForm() = runTest {
        val viewModel = ProductViewModel(FakeProductRepository())
        val date = LocalDate.now().plusDays(3)
        val product = ProductItem(1, "Milk", 2, date)

        viewModel.openEditSheet(product)

        assertEquals("Milk", viewModel.uiState.value.formName)
        assertEquals("2", viewModel.uiState.value.formQuantity)
        assertEquals(date, viewModel.uiState.value.formExpirationDate)
        assertTrue(viewModel.uiState.value.showAddSheet)
    }

    @Test
    fun addProduct_inEditMode_updatesExisting() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val date = LocalDate.now().plusDays(3)
        val product = ProductItem(0, "Milk", 2, date)
        repo.emit(listOf(product))

        viewModel.openEditSheet(product)
        viewModel.onFormNameChange("Oat Milk")
        viewModel.addProduct()

        val products = viewModel.uiState.value.products
        assertEquals(1, products.size)
        assertEquals("Oat Milk", products.first().name)
    }
}

private class FakeProductRepository : ProductRepository {
    private val _products = MutableStateFlow<List<ProductItem>?>(null)
    override val products: Flow<List<ProductItem>> = _products.filterNotNull()

    fun emit(items: List<ProductItem>) {
        _products.value = items
    }

    override suspend fun add(name: String, quantity: Int, expirationDate: LocalDate) {
        val current = _products.value ?: emptyList()
        _products.value = current + ProductItem(current.size, name, quantity, expirationDate)
    }

    override suspend fun update(uid: Int, name: String, quantity: Int, expirationDate: LocalDate) {
        _products.value = (_products.value ?: emptyList()).map {
            if (it.uid == uid) it.copy(name = name, quantity = quantity, expirationDate = expirationDate) else it
        }
    }

    override suspend fun delete(uid: Int) {
        _products.value = (_products.value ?: emptyList()).filter { it.uid != uid }
    }
}
