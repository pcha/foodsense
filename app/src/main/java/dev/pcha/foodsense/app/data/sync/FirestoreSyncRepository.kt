package dev.pcha.foodsense.app.data.sync

import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import kotlinx.coroutines.flow.Flow

data class FirestoreProduct(
    val serverId: String,
    val name: String,
    val items: List<FirestoreItem>,
)

data class FirestoreItem(
    val id: String,
    val quantity: Float,
    val unit: String?,
    val expirationDate: Long?,
    val addedAt: Long,
)

interface FirestoreSyncRepository {
    suspend fun upsertProduct(userId: String, product: ProductEntity, items: List<ItemEntity>): String
    suspend fun deleteProduct(userId: String, serverId: String)
    suspend fun updateProductItems(userId: String, productServerId: String, items: List<ItemEntity>)
    suspend fun fetchAll(userId: String): List<FirestoreProduct>
    fun listenToChanges(userId: String): Flow<List<FirestoreProduct>>
}
