package com.github.pcha.foodsense.app.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.github.pcha.foodsense.app.data.Item
import com.github.pcha.foodsense.app.data.Product
import com.github.pcha.foodsense.app.data.ProductRepository
import com.github.pcha.foodsense.app.data.DefaultProductRepository
import com.github.pcha.foodsense.app.data.local.database.ProductUnit
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Singleton
    @Binds
    fun bindsProductRepository(
        productRepository: DefaultProductRepository
    ): ProductRepository
}

class FakeProductRepository @Inject constructor() : ProductRepository {
    override val products: Flow<List<Product>> = flowOf(fakeProducts)
    override val productNames: Flow<List<String>> = flowOf(fakeProducts.map { it.name })

    override suspend fun add(name: String, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {}
    override suspend fun updateProduct(productId: Int, name: String) {}
    override suspend fun updateItem(itemId: Int, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {}
    override suspend fun deleteItem(itemId: Int) {}
    override suspend fun deleteProduct(productId: Int) {}
}

val fakeProducts = listOf(
    Product(
        uid = 1,
        name = "Milk",
        items = listOf(
            Item(1, 1, 1f, ProductUnit.L, LocalDate.now().plusDays(3), LocalDate.now()),
            Item(2, 1, 1f, ProductUnit.L, LocalDate.now().plusDays(10), LocalDate.now()),
        ),
    ),
    Product(
        uid = 2,
        name = "Eggs",
        items = listOf(
            Item(3, 2, 12f, null, LocalDate.now().plusDays(7), LocalDate.now()),
        ),
    ),
    Product(
        uid = 3,
        name = "Oranges",
        items = listOf(
            Item(4, 3, 5f, null, null, LocalDate.now().minusDays(3)),
            Item(5, 3, 3f, null, null, LocalDate.now()),
        ),
    ),
)
