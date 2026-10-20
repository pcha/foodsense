package dev.pcha.foodsense.app.data

import android.util.Log
import dev.pcha.foodsense.app.BuildConfig
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.auth.User
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
import dev.pcha.foodsense.app.data.sync.outbox.DisabledInventoryIntents
import dev.pcha.foodsense.app.data.sync.outbox.InventoryIntentRecorder
import dev.pcha.foodsense.app.data.sync.outbox.LocalInventoryOperations
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
    /** Defaulted to keep every existing construction site explicit; Hilt binds the real policy. */
    private val intents: InventoryIntentRecorder = DisabledInventoryIntents(),
) : ProductRepository {

    private val _syncStatus = MutableStateFlow(SyncStatus.Idle)
    override val syncStatus: Flow<SyncStatus> = _syncStatus.asStateFlow()

    /** Account the rows in Room belong to. Resolved on the first auth emission of the process. */
    private var ownerAccountId: String? = null
    private var ownerResolved = false

    init {
        appScope.launch {
            authRepository.currentUser.collectLatest { user ->
                handOverLocalData(user)
                if (user != null) {
                    _syncStatus.value = SyncStatus.Syncing
                    runCatching { migrateLocalDataToFirestore(user.uid) }
                    syncRepository.listenToChanges(user.uid)
                        .onEach { _syncStatus.value = SyncStatus.Idle }
                        .catch { _syncStatus.value = SyncStatus.Error }
                        .collect { applyRemoteChanges(user.uid, it) }
                } else {
                    _syncStatus.value = SyncStatus.Idle
                }
            }
        }
    }

    /**
     * Moves the database to the account that just emitted, dropping what the previous one left.
     *
     * Room holds one account's inventory, so a sign-out or a different account signing in has to take
     * that account's synced rows and its pending intents away together: otherwise the next account reads
     * somebody else's data, and V2 would deliver changes on its behalf.
     *
     * The first emission of a process cannot be compared with anything. Signed in, it trusts the
     * database it opens - the last sign-out purged it, and purging here would delete that user's offline
     * inventory on every launch. Signed out, nobody owns what is there, so leftovers from a session that
     * was killed mid-sign-out go now.
     *
     * The owner only moves after the purge has committed, so a cancelled transition is retried instead
     * of half-applied.
     *
     * @return true when the database was handed over (rows and intents were dropped).
     */
    internal suspend fun handOverLocalData(user: User?): Boolean {
        val uid = user?.uid
        if (!ownerResolved) {
            ownerResolved = true
            ownerAccountId = uid
            if (uid != null) return false
            clearLocalData()
            return true
        }
        if (uid == ownerAccountId) return false
        clearLocalData()
        ownerAccountId = uid
        return true
    }

    internal suspend fun clearLocalData() {
        localTransactions.run {
            // Keep local-only products (serverId == null) so data added offline before
            // signing in isn't lost; migrateLocalDataToFirestore uploads them on next login.
            productDao.deleteSyncedProducts()
            // Pending V2 intents belong to the account that is leaving: the synced rows they describe
            // are gone with it, and no other account may be charged with its changes.
            intents.discardPending()
        }
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
        val accountId = currentAccountId()
        val productId = localTransactions.run {
            val existing = productDao.findProductByName(name)
            val product = existing ?: ProductEntity(name, updatedAt = now)
            val id = existing?.uid ?: productDao.insertProduct(product).toInt()
            // A freshly inserted row only carries its key after Room assigns it.
            product.uid = id
            val items = List(batchSize) {
                ItemEntity(
                    productId = id,
                    quantity = quantity,
                    unit = unit,
                    expirationDate = expirationDate,
                    addedAt = LocalDate.now(),
                )
            }
            items.forEach { itemDao.insertItem(it) }
            productDao.touchUpdatedAt(id, now)
            // A product the V2 server has never seen travels with its first items, in one intent.
            intents.record(accountId, now) { LocalInventoryOperations.itemsChanged(listOf(product), items) }
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
        val accountId = currentAccountId()
        localTransactions.run {
            val current = productDao.findProductById(productId) ?: return@run
            val renamed = current.copy(name = name, updatedAt = now).also { it.uid = productId }
            productDao.updateProduct(renamed)
            intents.record(accountId, now) { LocalInventoryOperations.productChanged(renamed) }
        }
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
        val accountId = currentAccountId()
        val affectedProducts = localTransactions.run {
            val current = itemIds.distinct().mapNotNull { itemDao.getItem(it) }
            val updated = current.map { item ->
                item.copy(quantity = quantity, unit = unit, expirationDate = expirationDate)
                    .also { it.uid = item.uid }
            }
            updated.forEach { itemDao.updateItem(it) }
            val ids = current.map { it.productId }.distinct()
            ids.forEach { productDao.touchUpdatedAt(it, now) }
            // The intent describes the values the device now holds, with the revisions it knows.
            val products = ids.mapNotNull { productDao.findProductById(it) }
            intents.record(accountId, now) { LocalInventoryOperations.itemsChanged(products, updated) }
            ids
        }
        pushAffectedProducts(affectedProducts)
    }

    override suspend fun deleteItem(itemId: Int) {
        deleteItems(listOf(itemId))
    }

    override suspend fun deleteItems(itemIds: List<Int>) {
        val now = System.currentTimeMillis()
        val accountId = currentAccountId()
        val affectedProducts = localTransactions.run {
            val current = itemIds.distinct().mapNotNull { itemDao.getItem(it) }
            current.forEach { itemDao.deleteItem(it.uid) }
            val ids = current.map { it.productId }.distinct()
            ids.forEach { productDao.touchUpdatedAt(it, now) }
            intents.record(accountId, now) { LocalInventoryOperations.itemsDeleted(current) }
            ids
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
        val now = System.currentTimeMillis()
        val accountId = currentAccountId()
        val deleted = localTransactions.run {
            val product = productDao.findProductById(productId) ?: return@run null
            // The server keeps a product's items as separate documents, so the rows have to be read
            // before the cascade removes them: deleting a product has to tombstone them in the same
            // action, items first, or the server keeps an item pointing at a product that is gone.
            val items = itemDao.getItemsByProduct(product.uid)
            productDao.deleteProduct(productId)
            intents.record(accountId, now) { LocalInventoryOperations.productDeleted(product, items) }
            product
        }
        syncIfAuthenticated { userId ->
            deleted?.serverId?.let { syncRepository.deleteProduct(userId, it) }
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

    /** The account that owns these rows, or null while the device is used without one. */
    private suspend fun currentAccountId(): String? = authRepository.currentUser.first()?.uid

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
