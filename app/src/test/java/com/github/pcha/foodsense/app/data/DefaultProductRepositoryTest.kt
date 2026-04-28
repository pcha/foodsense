package com.github.pcha.foodsense.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.github.pcha.foodsense.app.data.local.database.Product
import com.github.pcha.foodsense.app.data.local.database.ProductDao
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProductRepositoryTest {

    @Test
    fun products_newItemSaved_itemIsReturned() = runTest {
        val repository = DefaultProductRepository(FakeProductDao())

        repository.add("Bread", 1, LocalDate.now())

        assertEquals(1, repository.products.first().size)
    }

    @Test
    fun products_itemUpdated_nameIsChanged() = runTest {
        val repository = DefaultProductRepository(FakeProductDao())
        val date = LocalDate.now()
        repository.add("Milk", 2, date)
        val uid = repository.products.first().first().uid

        repository.update(uid, "Oat Milk", 2, date)

        assertEquals("Oat Milk", repository.products.first().first().name)
    }

    @Test
    fun products_itemDeleted_isNotReturned() = runTest {
        val repository = DefaultProductRepository(FakeProductDao())
        repository.add("Eggs", 12, LocalDate.now())
        val uid = repository.products.first().first().uid

        repository.delete(uid)

        assertTrue(repository.products.first().isEmpty())
    }
}

private class FakeProductDao : ProductDao {
    private val _products = MutableStateFlow<List<Product>>(emptyList())

    override fun getProducts(): Flow<List<Product>> = _products

    override suspend fun insertProduct(item: Product) {
        val uid = (_products.value.maxOfOrNull { it.uid } ?: 0) + 1
        _products.value = _products.value + item.also { it.uid = uid }
    }

    override suspend fun updateProduct(item: Product) {
        _products.value = _products.value.map { if (it.uid == item.uid) item else it }
    }

    override suspend fun deleteProduct(uid: Int) {
        _products.value = _products.value.filter { it.uid != uid }
    }

    override suspend fun getProductsExpiringOn(epochDay: Long): List<Product> =
        _products.value.filter { it.expirationDate.toEpochDay() == epochDay }
}
