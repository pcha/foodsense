package dev.pcha.foodsense.app.data.sync

import kotlinx.coroutines.flow.Flow

data class FirestoreProduct(
    val serverId: String,
    val name: String,
    val items: List<FirestoreItem>,
    val updatedAt: Long = 0L,
)

data class FirestoreItem(
    val id: String,
    val quantity: Float,
    val unit: String?,
    val expirationDate: Long?,
    val addedAt: Long,
)

interface FirestoreSyncRepository {
    suspend fun upsertProduct(userId: String, product: FirestoreProduct): FirestoreProduct
    suspend fun deleteProduct(userId: String, serverId: String)
    /**
     * Returns the items as stored, with an id assigned to any that came in blank. The caller must
     * persist those ids: an item's local serverId is what marks it as confirmed on the server.
     */
    suspend fun updateProductItems(userId: String, productServerId: String, items: List<FirestoreItem>, updatedAt: Long): List<FirestoreItem>
    suspend fun fetchAll(userId: String): List<FirestoreProduct>
    fun listenToChanges(userId: String): Flow<List<FirestoreProduct>>
}
