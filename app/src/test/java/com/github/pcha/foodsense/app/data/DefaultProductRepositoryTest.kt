package com.github.pcha.foodsense.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.github.pcha.foodsense.app.data.local.database.ItemDao
import com.github.pcha.foodsense.app.data.local.database.ItemEntity
import com.github.pcha.foodsense.app.data.local.database.ProductDao
import com.github.pcha.foodsense.app.data.local.database.ProductEntity
import com.github.pcha.foodsense.app.data.local.database.ProductUnit
import com.github.pcha.foodsense.app.data.local.database.ProductWithItems
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProductRepositoryTest {

    private fun makeRepository(): Pair<DefaultProductRepository, FakeItemDao> {
        val itemDao = FakeItemDao()
        return Pair(DefaultProductRepository(FakeProductDao(itemDao), itemDao), itemDao)
    }

    @Test
    fun products_newItemSaved_itemIsReturned() = runTest {
        val (repository) = makeRepository()

        repository.add("Bread", 1f, null, LocalDate.now())

        val products = repository.products.first()
        assertEquals(1, products.size)
        assertEquals(1, products.first().items.size)
    }

    @Test
    fun products_itemUpdated_nameIsChanged() = runTest {
        val (repository) = makeRepository()
        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now())
        val productId = repository.products.first().first().uid

        repository.updateProduct(productId, "Oat Milk")

        assertEquals("Oat Milk", repository.products.first().first().name)
    }

    @Test
    fun products_itemDeleted_isNotReturned() = runTest {
        val (repository) = makeRepository()
        repository.add("Eggs", 12f, null, null)
        val itemId = repository.products.first().first().items.first().uid

        repository.deleteItem(itemId)

        assertTrue(repository.products.first().isEmpty())
    }

    @Test
    fun products_newItemWithUnit_unitIsReturned() = runTest {
        val (repository) = makeRepository()

        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now())

        assertEquals(ProductUnit.L, repository.products.first().first().items.first().unit)
    }

    @Test
    fun add_existingProduct_addsItemOnly() = runTest {
        val (repository) = makeRepository()
        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now())

        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now().plusDays(7))

        val products = repository.products.first()
        assertEquals(1, products.size)
        assertEquals(2, products.first().items.size)
    }

    @Test
    fun deleteItem_lastItem_removesProduct() = runTest {
        val (repository) = makeRepository()
        repository.add("Eggs", 12f, null, null)
        val itemId = repository.products.first().first().items.first().uid

        repository.deleteItem(itemId)

        assertTrue(repository.products.first().isEmpty())
    }

    @Test
    fun updateItem_changesQuantityAndDate() = runTest {
        val (repository) = makeRepository()
        val newDate = LocalDate.now().plusDays(3)
        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now().plusDays(30))
        val itemId = repository.products.first().first().items.first().uid

        repository.updateItem(itemId, 0.5f, ProductUnit.L, newDate)

        val item = repository.products.first().first().items.first()
        assertEquals(0.5f, item.quantity)
        assertEquals(newDate, item.expirationDate)
    }
}

private class FakeItemDao : ItemDao {
    private val _items = MutableStateFlow<List<ItemEntity>>(emptyList())
    val items: StateFlow<List<ItemEntity>> = _items.asStateFlow()
    private var nextUid = 1

    override suspend fun insertItem(item: ItemEntity) {
        _items.value = _items.value + item.also { it.uid = nextUid++ }
    }

    override suspend fun updateItem(item: ItemEntity) {
        _items.value = _items.value.map { if (it.uid == item.uid) item else it }
    }

    override suspend fun getItem(uid: Int): ItemEntity? = _items.value.find { it.uid == uid }

    override suspend fun deleteItem(uid: Int) {
        _items.value = _items.value.filter { it.uid != uid }
    }

    override suspend fun getProductNamesExpiringOn(epochDay: Long): List<String> = emptyList()
}

private class FakeProductDao(private val itemDao: FakeItemDao) : ProductDao {
    private val _products = MutableStateFlow<List<ProductEntity>>(emptyList())
    private var nextUid = 1

    override fun getProductsWithItems(): Flow<List<ProductWithItems>> =
        combine(_products, itemDao.items) { products, items ->
            products.map { p -> ProductWithItems(p, items.filter { it.productId == p.uid }) }
        }

    override suspend fun insertProduct(product: ProductEntity): Long {
        val uid = nextUid++.toLong()
        _products.value = _products.value + product.also { it.uid = uid.toInt() }
        return uid
    }

    override suspend fun updateProduct(product: ProductEntity) {
        _products.value = _products.value.map { if (it.uid == product.uid) product else it }
    }

    override suspend fun findProductByName(name: String): ProductEntity? =
        _products.value.find { it.name == name }

    override suspend fun deleteProductsWithNoItems() {
        val productIds = itemDao.items.value.map { it.productId }.toSet()
        _products.value = _products.value.filter { it.uid in productIds }
    }

    override suspend fun deleteProduct(uid: Int) {
        _products.value = _products.value.filter { it.uid != uid }
    }
}
