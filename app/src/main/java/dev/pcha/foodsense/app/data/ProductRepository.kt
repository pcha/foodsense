package dev.pcha.foodsense.app.data

import android.util.Log
import dev.pcha.foodsense.app.BuildConfig
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.di.ApplicationScope
import dev.pcha.foodsense.app.data.local.database.ItemDao
import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductDao
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import dev.pcha.foodsense.app.data.local.database.ProductUnit
import dev.pcha.foodsense.app.data.local.database.LocalTransactions
import dev.pcha.foodsense.app.data.sync.FirestoreItem
import dev.pcha.foodsense.app.data.sync.FirestoreProduct
import dev.pcha.foodsense.app.data.sync.FirestoreSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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

enum class SyncStatus { Idle, Syncing, Error }

interface ProductRepository {
    val products: Flow<List<Product>>
    val productNames: Flow<List<String>>
    val syncStatus: Flow<SyncStatus>

    suspend fun add(name: String, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?)
    suspend fun addBatch(name: String, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?, count: Int)
    suspend fun updateProduct(productId: Int, name: String)
    suspend fun updateItem(itemId: Int, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?)
    suspend fun updateItems(itemIds: List<Int>, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?)
    suspend fun deleteItem(itemId: Int)
    suspend fun deleteItems(itemIds: List<Int>)
    suspend fun deleteProduct(productId: Int)

    /** One-shot reconciliation (pull) used by the periodic sync worker. Returns false on failure. */
    suspend fun sync(): Boolean
}

class DefaultProductRepository @Inject constructor(
    private val productDao: ProductDao,
    private val itemDao: ItemDao,
    private val authRepository: AuthRepository,
    private val syncRepository: FirestoreSyncRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val localTransactions: LocalTransactions,
) : ProductRepository {

    private val _syncStatus = MutableStateFlow(SyncStatus.Idle)
    override val syncStatus: Flow<SyncStatus> = _syncStatus.asStateFlow()

    init {
        appScope.launch {
            authRepository.currentUser.collectLatest { user ->
                if (user != null) {
                    _syncStatus.value = SyncStatus.Syncing
                    runCatching { migrateLocalDataToFirestore(user.uid) }
                    syncRepository.listenToChanges(user.uid)
                        .onEach { _syncStatus.value = SyncStatus.Idle }
                        .catch { _syncStatus.value = SyncStatus.Error }
                        .collect { applyRemoteChanges(user.uid, it) }
                } else {
                    _syncStatus.value = SyncStatus.Idle
                    clearLocalData()
                }
            }
        }
    }

    internal suspend fun clearLocalData() {
        // Keep local-only products (serverId == null) so data added offline before
        // signing in isn't lost; migrateLocalDataToFirestore uploads them on next login.
        productDao.deleteSyncedProducts()
    }

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
            }.sortedWith(
                compareBy(nullsLast(naturalOrder())) { product: Product -> product.items.first().expirationDate }
            )
        }

    override val productNames: Flow<List<String>> = productDao.getAllProductNames()

    override suspend fun add(name: String, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {
        addBatch(name, quantity, unit, expirationDate, count = 1)
    }

    override suspend fun addBatch(
        name: String,
        quantity: Float,
        unit: ProductUnit?,
        expirationDate: LocalDate?,
        count: Int,
    ) {
        val batchSize = count.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val productId = localTransactions.run {
            val existing = productDao.findProductByName(name)
            val id = existing?.uid ?: productDao.insertProduct(ProductEntity(name, updatedAt = now)).toInt()
            repeat(batchSize) {
                itemDao.insertItem(ItemEntity(
                    productId = id,
                    quantity = quantity,
                    unit = unit,
                    expirationDate = expirationDate,
                    addedAt = LocalDate.now(),
                ))
            }
            productDao.touchUpdatedAt(id, now)
            id
        }
        syncLog("ADD local productId=$productId name='$name' count=$batchSize itemQuantity=$quantity unit=$unit expirationDate=$expirationDate")
        syncIfAuthenticated { userId ->
            val product = productDao.findProductByName(name) ?: return@syncIfAuthenticated
            val items = itemDao.getItemsByProduct(product.uid)
            val serverId = product.serverId ?: run {
                // Assign serverId in Room BEFORE calling Firestore so the snapshot listener
                // finds the product by serverId and doesn't insert a duplicate.
                val result = syncRepository.upsertProduct(userId, product.toFirestoreProduct(items))
                productDao.updateServerId(product.uid, result.serverId)
                persistItemServerIds(items, result.items)
                return@syncIfAuthenticated
            }
            val result = syncRepository.upsertProduct(
                userId, product.copy(serverId = serverId).toFirestoreProduct(items)
            )
            persistItemServerIds(items, result.items)
        }
    }

    override suspend fun updateProduct(productId: Int, name: String) {
        val now = System.currentTimeMillis()
        val current = productDao.findProductById(productId) ?: return
        productDao.updateProduct(current.copy(name = name, updatedAt = now).also { it.uid = productId })
        syncIfAuthenticated { userId ->
            val product = productDao.findProductById(productId) ?: return@syncIfAuthenticated
            if (product.serverId != null) {
                val items = itemDao.getItemsByProduct(product.uid)
                val result = syncRepository.upsertProduct(userId, product.toFirestoreProduct(items))
                persistItemServerIds(items, result.items)
            }
        }
    }

    override suspend fun updateItem(itemId: Int, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {
        updateItems(listOf(itemId), quantity, unit, expirationDate)
    }

    override suspend fun updateItems(itemIds: List<Int>, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {
        val now = System.currentTimeMillis()
        val affectedProducts = localTransactions.run {
            val current = itemIds.distinct().mapNotNull { itemDao.getItem(it) }
            current.forEach { item ->
                itemDao.updateItem(
                    item.copy(quantity = quantity, unit = unit, expirationDate = expirationDate)
                        .also { it.uid = item.uid }
                )
            }
            current.map { it.productId }.distinct().also { ids ->
                ids.forEach { productDao.touchUpdatedAt(it, now) }
            }
        }
        pushAffectedProducts(affectedProducts)
    }

    override suspend fun deleteItem(itemId: Int) {
        deleteItems(listOf(itemId))
    }

    override suspend fun deleteItems(itemIds: List<Int>) {
        val now = System.currentTimeMillis()
        val affectedProducts = localTransactions.run {
            val current = itemIds.distinct().mapNotNull { itemDao.getItem(it) }
            current.forEach { itemDao.deleteItem(it.uid) }
            current.map { it.productId }.distinct().also { ids ->
                ids.forEach { productDao.touchUpdatedAt(it, now) }
            }
        }
        pushAffectedProducts(affectedProducts)
    }

    private suspend fun pushAffectedProducts(productIds: List<Int>) {
        if (productIds.isEmpty()) return
        syncIfAuthenticated { userId ->
            for (productId in productIds) {
                val product = productDao.findProductById(productId) ?: continue
                val items = itemDao.getItemsByProduct(productId)
                if (product.serverId != null) {
                    if (items.isEmpty()) {
                        syncRepository.deleteProduct(userId, product.serverId)
                    } else {
                        val resolved = syncRepository.updateProductItems(
                            userId, product.serverId, items.map { it.toFirestoreItem() }, product.updatedAt
                        )
                        persistItemServerIds(items, resolved)
                    }
                }
            }
        }
    }

    override suspend fun deleteProduct(productId: Int) {
        val product = productDao.findProductById(productId)
        productDao.deleteProduct(productId)
        syncIfAuthenticated { userId ->
            product?.serverId?.let { syncRepository.deleteProduct(userId, it) }
        }
    }

    override suspend fun sync(): Boolean {
        val user = authRepository.currentUser.first() ?: return true
        _syncStatus.value = SyncStatus.Syncing
        return runCatching {
            migrateLocalDataToFirestore(user.uid)
            applyRemoteChanges(user.uid, syncRepository.fetchAll(user.uid))
        }.onSuccess { _syncStatus.value = SyncStatus.Idle }
            .onFailure { _syncStatus.value = SyncStatus.Error }
            .isSuccess
    }

    private suspend fun migrateLocalDataToFirestore(userId: String) {
        for (product in productDao.getProductsWithoutServerId()) {
            val items = itemDao.getItemsByProduct(product.uid)
            val result = syncRepository.upsertProduct(userId, product.toFirestoreProduct(items))
            productDao.updateServerId(product.uid, result.serverId)
            persistItemServerIds(items, result.items)
        }
    }

    /**
     * Records the ids Firestore assigned. Only ever called after a push succeeded: an item whose
     * serverId is set means "confirmed on the server", and [applyRemoteChanges] relies on that to
     * tell a genuinely local item from one it should let the remote replace.
     *
     * Assigning ids up front instead would be simpler but wrong — a push that then failed would
     * leave the item looking synced, and the next snapshot would delete it.
     *
     * `upsertProduct` and `updateProductItems` both map their input 1:1 preserving order, so the
     * lists line up by index.
     */
    private suspend fun persistItemServerIds(local: List<ItemEntity>, remote: List<FirestoreItem>) {
        local.zip(remote).forEach { (localItem, remoteItem) ->
            if (localItem.serverId == null) itemDao.updateServerId(localItem.uid, remoteItem.id)
        }
    }

    internal suspend fun applyRemoteChanges(userId: String, remoteProducts: List<FirestoreProduct>) {
        syncLog("APPLY snapshot products=${remoteProducts.size}: ${remoteProducts.joinToString { product -> "name='${product.name}' serverId=${product.serverId} updatedAt=${product.updatedAt} items=${product.items.map(::itemLog)}" }}")
        val remoteServerIds = remoteProducts.mapTo(mutableSetOf()) { it.serverId }
        val localSynced = productDao.getProductsWithItems().first()
            .filter { it.product.serverId != null }

        for (local in localSynced) {
            if (local.product.serverId !in remoteServerIds) {
                syncLog("DELETE local missing remotely productId=${local.product.uid} serverId=${local.product.serverId} name='${local.product.name}' items=${local.items.map(::itemLog)}")
                productDao.deleteProduct(local.product.uid)
            }
        }

        // Name is the natural key (add() appends items to the product found by name), so every
        // doc sharing a name collapses into one local row and one surviving doc. Two devices can
        // each create "Milk" before their first sync; those are the same product with two items,
        // not competing writes, so the items are unioned rather than resolved by timestamp.
        for ((name, group) in remoteProducts.groupBy { it.name }) {
            // Lexicographic min: every device picks the same winner from the same snapshot
            // without coordinating.
            val winner = group.minBy { it.serverId }
            val mergedItems = group.flatMap { it.items }.distinctBy { it.id }
            val mergedUpdatedAt = group.maxOf { it.updatedAt }

            val local = group.firstNotNullOfOrNull { productDao.findProductByServerId(it.serverId) }
                ?: productDao.findProductByName(name)

            if (local == null) {
                syncLog("APPLY insert remote name='$name' winner=${winner.serverId} mergedFrom=${group.map { it.serverId }} updatedAt=$mergedUpdatedAt items=${mergedItems.map(::itemLog)}")
                val productId = productDao.insertProduct(
                    ProductEntity(name = name, serverId = winner.serverId, updatedAt = mergedUpdatedAt)
                ).toInt()
                itemDao.replaceItems(productId, mergedItems.map { it.toItemEntity(productId) })
            } else {
                // Last-write-wins: skip stale remote so a newer un-synced local edit isn't clobbered.
                val localItems = itemDao.getItemsByProduct(local.uid)
                if (mergedUpdatedAt < local.updatedAt) {
                    syncLog("APPLY skip stale name='$name' productId=${local.uid} serverId=${local.serverId} localUpdatedAt=${local.updatedAt} remoteUpdatedAt=$mergedUpdatedAt localItems=${localItems.map(::itemLog)} remoteItems=${mergedItems.map(::itemLog)}")
                    continue
                }
                val updatedProduct = local.copy(name = name, serverId = winner.serverId, updatedAt = mergedUpdatedAt)
                    .also { it.uid = local.uid }
                if (local != updatedProduct) productDao.updateProduct(updatedProduct)
                val sameItems = localItems.size == mergedItems.size &&
                    localItems.map { it.toFirestoreItem() }.toSet() == mergedItems.toSet()
                if (sameItems) {
                    syncLog("APPLY unchanged items name='$name' productId=${local.uid}; preserving local item IDs")
                } else {
                    syncLog("APPLY replace atomically name='$name' productId=${local.uid} localItems=${localItems.map(::itemLog)} remoteItems=${mergedItems.map(::itemLog)}")
                    itemDao.replaceItems(local.uid, mergedItems.map { it.toItemEntity(local.uid) })
                }
            }

            // Converge the remote too. Pushing the union before deleting the losers keeps the
            // items safe if another device processes the delete first; leaving the losers alive
            // would let every later snapshot resurrect their items.
            val losers = group.filter { it.serverId != winner.serverId }
            if (losers.isNotEmpty()) {
                syncLog("MERGE duplicate product name='$name' winner=${winner.serverId} losers=${losers.map { it.serverId }} mergedItems=${mergedItems.map(::itemLog)}")
                syncRepository.updateProductItems(userId, winner.serverId, mergedItems, mergedUpdatedAt)
                losers.forEach { syncRepository.deleteProduct(userId, it.serverId) }
            }
        }
    }

    private suspend fun syncIfAuthenticated(block: suspend (userId: String) -> Unit) {
        val user = authRepository.currentUser.first() ?: return
        appScope.launch {
            runCatching { block(user.uid) }
                .onFailure {
                    if (BuildConfig.DEBUG) Log.e(TAG, "Firestore sync operation failed", it)
                    _syncStatus.value = SyncStatus.Error
                }
        }
    }

    private fun itemLog(item: ItemEntity) =
        "{id=${item.uid},serverId=${item.serverId},quantity=${item.quantity},unit=${item.unit},expiration=${item.expirationDate},added=${item.addedAt}}"

    private fun itemLog(item: FirestoreItem) =
        "{id=${item.id},quantity=${item.quantity},unit=${item.unit},expirationEpochDay=${item.expirationDate},addedEpochDay=${item.addedAt}}"

    private fun syncLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "FoodSenseSync"
    }

    private fun ProductEntity.toFirestoreProduct(items: List<ItemEntity>) = FirestoreProduct(
        serverId = serverId ?: "",
        name = name,
        items = items.map { it.toFirestoreItem() },
        updatedAt = updatedAt,
    )

    private fun ItemEntity.toFirestoreItem() = FirestoreItem(
        id = serverId ?: "",
        quantity = quantity,
        unit = unit?.name,
        expirationDate = expirationDate?.toEpochDay(),
        addedAt = addedAt.toEpochDay(),
    )
}

private fun FirestoreItem.toItemEntity(productId: Int) = ItemEntity(
    productId = productId,
    quantity = quantity,
    unit = unit?.let { runCatching { ProductUnit.valueOf(it) }.getOrNull() },
    expirationDate = expirationDate?.let { LocalDate.ofEpochDay(it) },
    addedAt = LocalDate.ofEpochDay(addedAt),
    serverId = id,
)
