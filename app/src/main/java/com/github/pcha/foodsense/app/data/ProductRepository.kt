package com.github.pcha.foodsense.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.github.pcha.foodsense.app.data.local.database.Product
import com.github.pcha.foodsense.app.data.local.database.ProductDao
import java.time.LocalDate
import javax.inject.Inject

data class ProductItem(
    val uid: Int,
    val name: String,
    val quantity: Int,
    val expirationDate: LocalDate
)

interface ProductRepository {
    val products: Flow<List<ProductItem>>

    suspend fun add(name: String, quantity: Int, expirationDate: LocalDate)
    suspend fun update(uid: Int, name: String, quantity: Int, expirationDate: LocalDate)
    suspend fun delete(uid: Int)
}

class DefaultProductRepository @Inject constructor(
    private val productDao: ProductDao
) : ProductRepository {

    override val products: Flow<List<ProductItem>> =
        productDao.getProducts().map { items ->
            items.map { ProductItem(it.uid, it.name, it.quantity, it.expirationDate) }
        }

    override suspend fun add(name: String, quantity: Int, expirationDate: LocalDate) {
        productDao.insertProduct(Product(name = name, quantity = quantity, expirationDate = expirationDate))
    }

    override suspend fun update(uid: Int, name: String, quantity: Int, expirationDate: LocalDate) {
        productDao.updateProduct(Product(name = name, quantity = quantity, expirationDate = expirationDate).also { it.uid = uid })
    }

    override suspend fun delete(uid: Int) {
        productDao.deleteProduct(uid)
    }
}
