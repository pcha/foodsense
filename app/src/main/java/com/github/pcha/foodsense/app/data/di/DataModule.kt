package com.github.pcha.foodsense.app.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.github.pcha.foodsense.app.data.ProductItem
import com.github.pcha.foodsense.app.data.ProductRepository
import com.github.pcha.foodsense.app.data.DefaultProductRepository
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
    override val products: Flow<List<ProductItem>> = flowOf(fakeProducts)

    override suspend fun add(name: String, quantity: Int, expirationDate: LocalDate) {}
    override suspend fun update(uid: Int, name: String, quantity: Int, expirationDate: LocalDate) {}
    override suspend fun delete(uid: Int) {}
}

val fakeProducts = listOf(
    ProductItem(1, "Milk", 2, LocalDate.now().plusDays(3)),
    ProductItem(2, "Eggs", 12, LocalDate.now().plusDays(7)),
    ProductItem(3, "Bread", 1, LocalDate.now().plusDays(1)),
)
