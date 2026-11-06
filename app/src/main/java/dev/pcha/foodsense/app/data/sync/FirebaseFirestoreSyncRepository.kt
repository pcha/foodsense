package dev.pcha.foodsense.app.data.sync

import android.util.Log
import dev.pcha.foodsense.app.BuildConfig
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject

class FirebaseFirestoreSyncRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
) : FirestoreSyncRepository {

    private fun productsCollection(userId: String) =
        firestore.collection("users").document(userId).collection("products")

    override suspend fun upsertProduct(userId: String, product: FirestoreProduct): FirestoreProduct {
        syncLog("PUSH upsert name='${product.name}' serverId=${product.serverId.ifBlank { "<new>" }} updatedAt=${product.updatedAt} items=${product.items.map(::itemLog)}")
        val resolvedItems = product.items.map { item ->
            if (item.id.isBlank()) item.copy(id = UUID.randomUUID().toString()) else item
        }
        val data = mapOf(
            "name" to product.name,
            "updatedAt" to product.updatedAt,
            "items" to resolvedItems.map { item ->
                mapOf(
                    "id" to item.id,
                    "quantity" to item.quantity,
                    "unit" to item.unit,
                    "expirationDate" to item.expirationDate,
                    "addedAt" to item.addedAt,
                )
            },
        )
        val collection = productsCollection(userId)
        val resolvedServerId = upsertProductDocument(collection, product.serverId, data)
        syncLog("PUSH upsert complete name='${product.name}' serverId=$resolvedServerId items=${resolvedItems.map(::itemLog)}")
        return product.copy(serverId = resolvedServerId, items = resolvedItems)
    }

    private suspend fun upsertProductDocument(
        collection: CollectionReference,
        serverId: String,
        data: Map<String, Any?>,
    ): String {
        if (serverId.isNotBlank()) {
            collection.document(serverId).set(data, SetOptions.merge()).await()
            return serverId
        }
        return collection.add(data).await().id
    }

    override suspend fun deleteProduct(userId: String, serverId: String) {
        syncLog("PUSH delete serverId=$serverId")
        productsCollection(userId).document(serverId).delete().await()
    }

    override suspend fun updateProductItems(
        userId: String,
        productServerId: String,
        items: List<FirestoreItem>,
        updatedAt: Long,
    ): List<FirestoreItem> {
        syncLog("PUSH updateItems serverId=$productServerId updatedAt=$updatedAt items=${items.map(::itemLog)}")
        val resolvedItems = items.map { item ->
            if (item.id.isBlank()) item.copy(id = UUID.randomUUID().toString()) else item
        }
        val data = mapOf(
            "updatedAt" to updatedAt,
            "items" to resolvedItems.map { item ->
                mapOf(
                    "id" to item.id,
                    "quantity" to item.quantity,
                    "unit" to item.unit,
                    "expirationDate" to item.expirationDate,
                    "addedAt" to item.addedAt,
                )
            },
        )
        productsCollection(userId).document(productServerId).set(data, SetOptions.merge()).await()
        return resolvedItems
    }

    override suspend fun fetchAll(userId: String): List<FirestoreProduct> {
        return productsCollection(userId).get().await().documents.mapNotNull { doc ->
            doc.toFirestoreProduct()
        }
    }

    override fun listenToChanges(userId: String): Flow<List<FirestoreProduct>> = callbackFlow {
        val listener = productsCollection(userId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "snapshot listener failed", error)
                close(error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            val products = snapshot.documents.mapNotNull { it.toFirestoreProduct() }
            trySend(products)
            syncLog("PULL snapshot documentCount=${snapshot.size()} parsed=${products.size} fromCache=${snapshot.metadata.isFromCache} products=${products.map(::productLog)}")
        }
        awaitClose { listener.remove() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun com.google.firebase.firestore.DocumentSnapshot.toFirestoreProduct(): FirestoreProduct? {
        val name = getString("name") ?: return null
        val rawItems = get("items") as? List<Map<String, Any?>> ?: emptyList()
        val items = rawItems.map { map ->
            FirestoreItem(
                id = map["id"] as? String ?: UUID.randomUUID().toString(),
                quantity = (map["quantity"] as? Number)?.toFloat() ?: 1f,
                unit = map["unit"] as? String,
                expirationDate = (map["expirationDate"] as? Number)?.toLong(),
                addedAt = (map["addedAt"] as? Number)?.toLong() ?: 0L,
            )
        }
        val updatedAt = (get("updatedAt") as? Number)?.toLong() ?: 0L
        return FirestoreProduct(serverId = id, name = name, items = items, updatedAt = updatedAt)
    }

    private companion object {
        const val TAG = "FoodSenseSync"
    }

    private fun itemLog(item: FirestoreItem) =
        "{id=${item.id},quantity=${item.quantity},unit=${item.unit},expirationEpochDay=${item.expirationDate},addedEpochDay=${item.addedAt}}"

    private fun productLog(product: FirestoreProduct) =
        "{name='${product.name}',serverId=${product.serverId},updatedAt=${product.updatedAt},items=${product.items.map(::itemLog)}}"

    private fun syncLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
