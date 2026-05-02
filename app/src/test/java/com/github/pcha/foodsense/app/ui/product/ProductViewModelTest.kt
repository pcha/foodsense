package com.github.pcha.foodsense.app.ui.product

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
import com.github.pcha.foodsense.app.data.Item
import com.github.pcha.foodsense.app.data.Product
import com.github.pcha.foodsense.app.data.ProductRepository
import com.github.pcha.foodsense.app.data.local.database.ProductUnit
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
        val today = LocalDate.now()
        val product = Product(0, "Milk", listOf(Item(0, 0, 1f, ProductUnit.L, today, today)))
        repo.emit(listOf(product))

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(1, viewModel.uiState.value.products.size)
        assertEquals("Milk", viewModel.uiState.value.products.first().name)
    }

    @Test
    fun addProduct_validInput_addsToList() = runTest {
        val viewModel = ProductViewModel(FakeProductRepository())

        viewModel.onFormNameChange("Bread")
        viewModel.onFormQuantityChange("1")
        viewModel.addProduct()

        assertEquals(1, viewModel.uiState.value.products.size)
        assertEquals("Bread", viewModel.uiState.value.products.first().name)
        assertEquals(1, viewModel.uiState.value.products.first().items.size)
    }

    @Test
    fun addProduct_withBatchCount_createsMultipleItems() = runTest {
        val viewModel = ProductViewModel(FakeProductRepository())
        val date = LocalDate.now().plusDays(5)

        viewModel.onFormNameChange("Milk")
        viewModel.onFormQuantityChange("1")
        viewModel.onFormUnitChange(ProductUnit.L)
        viewModel.onFormBatchCountChange("3")
        viewModel.onFormDateSelected(date)
        viewModel.addProduct()

        assertEquals(1, viewModel.uiState.value.products.size)
        with(viewModel.uiState.value.products.first()) {
            assertEquals("Milk", name)
            assertEquals(3, items.size)
            assertTrue(items.all { it.unit == ProductUnit.L && it.quantity == 1f })
        }
    }

    @Test
    fun deleteItem_removesFromList() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val today = LocalDate.now()
        val item = Item(0, 0, 12f, null, null, today)
        repo.emit(listOf(Product(0, "Eggs", listOf(item))))

        viewModel.deleteItem(item.uid)

        assertTrue(viewModel.uiState.value.products.isEmpty())
    }

    @Test
    fun openEditSheet_preloadsForm() = runTest {
        val viewModel = ProductViewModel(FakeProductRepository())
        val product = Product(1, "Milk", emptyList())

        viewModel.openEditSheet(product)

        assertEquals("Milk", viewModel.uiState.value.formName)
        assertEquals(1, viewModel.uiState.value.editingProductId)
        assertTrue(viewModel.uiState.value.showAddSheet)
    }

    @Test
    fun addProduct_inEditMode_updatesProductName() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val today = LocalDate.now()
        val item = Item(0, 0, 1f, ProductUnit.L, today, today)
        repo.emit(listOf(Product(0, "Milk", listOf(item))))

        viewModel.openEditSheet(viewModel.uiState.value.products.first())
        viewModel.onFormNameChange("Oat Milk")
        viewModel.addProduct()

        val products = viewModel.uiState.value.products
        assertEquals(1, products.size)
        assertEquals("Oat Milk", products.first().name)
    }

    @Test
    fun openQuickAddSheet_preloadsName() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val today = LocalDate.now()
        repo.emit(listOf(Product(1, "Milk", listOf(Item(1, 1, 1f, ProductUnit.L, today, today)))))

        viewModel.openQuickAddSheet(viewModel.uiState.value.products.first())

        assertEquals("Milk", viewModel.uiState.value.formName)
        assertEquals(1, viewModel.uiState.value.addingToProductId)
        assertTrue(viewModel.uiState.value.showAddSheet)
    }

    @Test
    fun addProduct_inQuickAddMode_addsItemToExistingProduct() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val today = LocalDate.now()
        repo.emit(listOf(Product(1, "Milk", listOf(Item(1, 1, 1f, ProductUnit.L, today, today)))))

        viewModel.openQuickAddSheet(viewModel.uiState.value.products.first())
        viewModel.onFormQuantityChange("1")
        viewModel.addProduct()

        val products = viewModel.uiState.value.products
        assertEquals(1, products.size)
        assertEquals(2, products.first().items.size)
    }

    @Test
    fun openEditItemSheet_preloadsGroupValues() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val date = LocalDate.now().plusDays(10)
        val items = listOf(
            Item(1, 1, 1f, ProductUnit.L, date, LocalDate.now()),
            Item(2, 1, 1f, ProductUnit.L, date, LocalDate.now()),
        )
        repo.emit(listOf(Product(1, "Milk", items)))

        viewModel.openEditItemSheet(items)

        assertEquals("1", viewModel.uiState.value.formQuantity)
        assertEquals(ProductUnit.L, viewModel.uiState.value.formUnit)
        assertEquals(date, viewModel.uiState.value.formExpirationDate)
        assertEquals(2, viewModel.uiState.value.maxApplyCount)
        assertEquals("1", viewModel.uiState.value.formApplyCount)
        assertEquals(listOf(1, 2), viewModel.uiState.value.editingGroupItemIds)
        assertTrue(viewModel.uiState.value.showAddSheet)
    }

    @Test
    fun addProduct_inEditGroupMode_updatesAllItems() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val today = LocalDate.now()
        val newDate = today.plusDays(30)
        val items = listOf(
            Item(1, 1, 1f, ProductUnit.L, today, today),
            Item(2, 1, 1f, ProductUnit.L, today, today),
        )
        repo.emit(listOf(Product(1, "Milk", items)))

        viewModel.openEditItemSheet(items)
        viewModel.onFormApplyCountChange("2")
        viewModel.onFormDateSelected(newDate)
        viewModel.addProduct()

        val updatedItems = viewModel.uiState.value.products.first().items
        assertTrue(updatedItems.all { it.expirationDate == newDate })
    }

    @Test
    fun deleteItems_removesMultipleFromGroup() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val today = LocalDate.now()
        val items = listOf(
            Item(1, 1, 1f, ProductUnit.L, today, today),
            Item(2, 1, 1f, ProductUnit.L, today, today),
            Item(3, 1, 1f, ProductUnit.L, today, today),
        )
        repo.emit(listOf(Product(1, "Milk", items)))

        viewModel.deleteItems(listOf(1, 2))

        val remaining = viewModel.uiState.value.products.first().items
        assertEquals(1, remaining.size)
        assertEquals(3, remaining.first().uid)
    }

    @Test
    fun addProduct_inEditGroupMode_partialCount_updatesOnlyN() = runTest {
        val repo = FakeProductRepository()
        val viewModel = ProductViewModel(repo)
        val today = LocalDate.now()
        val newDate = today.plusDays(90)
        val items = listOf(
            Item(1, 1, 1f, ProductUnit.L, today, today),
            Item(2, 1, 1f, ProductUnit.L, today, today),
            Item(3, 1, 1f, ProductUnit.L, today, today),
        )
        repo.emit(listOf(Product(1, "Milk", items)))

        viewModel.openEditItemSheet(items)
        viewModel.onFormApplyCountChange("1")
        viewModel.onFormQuantityChange("0.5")
        viewModel.onFormDateSelected(newDate)
        viewModel.addProduct()

        val updatedItems = viewModel.uiState.value.products.first().items
        assertEquals(1, updatedItems.count { it.quantity == 0.5f && it.expirationDate == newDate })
        assertEquals(2, updatedItems.count { it.quantity == 1f && it.expirationDate == today })
    }
}

private class FakeProductRepository : ProductRepository {
    private val _products = MutableStateFlow<List<Product>?>(null)
    override val products: Flow<List<Product>> = _products.filterNotNull()
    override val productNames: Flow<List<String>> = _products.filterNotNull().map { it.map { p -> p.name } }
    private var nextProductUid = 1
    private var nextItemUid = 1

    fun emit(products: List<Product>) {
        _products.value = products
        nextProductUid = (products.maxOfOrNull { it.uid } ?: 0) + 1
        nextItemUid = (products.flatMap { it.items }.maxOfOrNull { it.uid } ?: 0) + 1
    }

    override suspend fun add(name: String, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {
        val current = _products.value ?: emptyList()
        val existing = current.find { it.name == name }
        if (existing != null) {
            val newItem = Item(nextItemUid++, existing.uid, quantity, unit, expirationDate, LocalDate.now())
            _products.value = current.map { if (it.uid == existing.uid) it.copy(items = it.items + newItem) else it }
        } else {
            val productId = nextProductUid++
            val newItem = Item(nextItemUid++, productId, quantity, unit, expirationDate, LocalDate.now())
            _products.value = current + Product(productId, name, listOf(newItem))
        }
    }

    override suspend fun updateProduct(productId: Int, name: String) {
        _products.value = (_products.value ?: emptyList()).map {
            if (it.uid == productId) it.copy(name = name) else it
        }
    }

    override suspend fun updateItem(itemId: Int, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {
        _products.value = (_products.value ?: emptyList()).map { product ->
            product.copy(items = product.items.map { item ->
                if (item.uid == itemId) item.copy(quantity = quantity, unit = unit, expirationDate = expirationDate)
                else item
            })
        }
    }

    override suspend fun deleteItem(itemId: Int) {
        _products.value = (_products.value ?: emptyList())
            .map { it.copy(items = it.items.filter { item -> item.uid != itemId }) }
            .filter { it.items.isNotEmpty() }
    }

    override suspend fun deleteProduct(productId: Int) {
        _products.value = (_products.value ?: emptyList()).filter { it.uid != productId }
    }
}
