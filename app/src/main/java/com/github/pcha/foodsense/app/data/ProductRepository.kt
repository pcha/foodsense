package com.github.pcha.foodsense.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.github.pcha.foodsense.app.data.local.database.ItemDao
import com.github.pcha.foodsense.app.data.local.database.ItemEntity
import com.github.pcha.foodsense.app.data.local.database.ProductDao
import com.github.pcha.foodsense.app.data.local.database.ProductEntity
import com.github.pcha.foodsense.app.data.local.database.ProductUnit
import java.time.LocalDate
import javax.inject.Inject

data class Item(
    val uid: Int,
    val productId: Int,
    val quantity: Float,
    val unit: ProductUnit?,
    val expirationDate: LocalDate?,
    val addedAt: LocalDate,
)

data class Product(
    val uid: Int,
    val name: String,
    val items: List<Item>,
)

interface ProductRepository {
    val products: Flow<List<Product>>
    val productNames: Flow<List<String>>

    suspend fun add(name: String, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?)
    suspend fun updateProduct(productId: Int, name: String)
    suspend fun updateItem(itemId: Int, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?)
    suspend fun deleteItem(itemId: Int)
    suspend fun deleteProduct(productId: Int)
}

class DefaultProductRepository @Inject constructor(
    private val productDao: ProductDao,
    private val itemDao: ItemDao,
) : ProductRepository {

    override val products: Flow<List<Product>> =
        productDao.getProductsWithItems().map { list ->
            list.filter { it.items.isNotEmpty() }.map { pwi ->
                Product(
                    uid = pwi.product.uid,
                    name = pwi.product.name,
                    items = pwi.items
                        .sortedWith(
                            compareBy<ItemEntity, LocalDate?>(nullsLast(naturalOrder())) { it.expirationDate }
                                .thenBy { it.addedAt }
                        )
                        .map { Item(it.uid, it.productId, it.quantity, it.unit, it.expirationDate, it.addedAt) },
                )
            }
        }

    override val productNames: Flow<List<String>> = productDao.getAllProductNames()

    override suspend fun add(name: String, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {
        val existing = productDao.findProductByName(name)
        val productId = existing?.uid ?: productDao.insertProduct(ProductEntity(name)).toInt()
        itemDao.insertItem(ItemEntity(
            productId = productId,
            quantity = quantity,
            unit = unit,
            expirationDate = expirationDate,
            addedAt = LocalDate.now(),
        ))
    }

    override suspend fun updateProduct(productId: Int, name: String) {
        productDao.updateProduct(ProductEntity(name).also { it.uid = productId })
    }

    override suspend fun updateItem(itemId: Int, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {
        val current = itemDao.getItem(itemId) ?: return
        itemDao.updateItem(
            current.copy(quantity = quantity, unit = unit, expirationDate = expirationDate).also { it.uid = itemId }
        )
    }

    override suspend fun deleteItem(itemId: Int) {
        itemDao.deleteItem(itemId)
    }

    override suspend fun deleteProduct(productId: Int) {
        productDao.deleteProduct(productId)
    }
}
