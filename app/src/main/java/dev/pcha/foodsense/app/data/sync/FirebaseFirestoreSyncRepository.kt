package dev.pcha.foodsense.app.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity
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

    override suspend fun upsertProduct(
        userId: String,
        product: ProductEntity,
        items: List<ItemEntity>,
    ): String {
        val data = mapOf(
            "name" to product.name,
            "items" to items.map { item ->
                mapOf(
                    "id" to (item.serverId ?: UUID.randomUUID().toString()),
                    "quantity" to item.quantity,
                    "unit" to item.unit?.name,
                    "expirationDate" to item.expirationDate?.toEpochDay(),
                    "addedAt" to item.addedAt.toEpochDay(),
                )
            },
        )
        val collection = productsCollection(userId)
        return if (product.serverId != null) {
            collection.document(product.serverId).set(data, SetOptions.merge()).await()
            product.serverId
        } else {
            collection.add(data).await().id
        }
    }

    override suspend fun deleteProduct(userId: String, serverId: String) {
        productsCollection(userId).document(serverId).delete().await()
    }

    override suspend fun updateProductItems(
        userId: String,
        productServerId: String,
        items: List<ItemEntity>,
    ) {
        val data = mapOf(
            "items" to items.map { item ->
                mapOf(
                    "id" to (item.serverId ?: UUID.randomUUID().toString()),
                    "quantity" to item.quantity,
                    "unit" to item.unit?.name,
                    "expirationDate" to item.expirationDate?.toEpochDay(),
                    "addedAt" to item.addedAt.toEpochDay(),
                )
            },
        )
        productsCollection(userId).document(productServerId).set(data, SetOptions.merge()).await()
    }

    override suspend fun fetchAll(userId: String): List<FirestoreProduct> {
        return productsCollection(userId).get().await().documents.mapNotNull { doc ->
            doc.toFirestoreProduct()
        }
    }

    override fun listenToChanges(userId: String): Flow<List<FirestoreProduct>> = callbackFlow {
        val listener = productsCollection(userId).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            trySend(snapshot.documents.mapNotNull { it.toFirestoreProduct() })
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
        return FirestoreProduct(serverId = id, name = name, items = items)
    }
}
