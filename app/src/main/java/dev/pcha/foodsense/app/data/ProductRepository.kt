package dev.pcha.foodsense.app.data

import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.di.ApplicationScope
import dev.pcha.foodsense.app.data.local.database.ItemDao
import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductDao
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import dev.pcha.foodsense.app.data.local.database.ProductUnit
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
    suspend fun updateProduct(productId: Int, name: String)
    suspend fun updateItem(itemId: Int, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?)
    suspend fun deleteItem(itemId: Int)
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
        val now = System.currentTimeMillis()
        val existing = productDao.findProductByName(name)
        val productId = existing?.uid ?: productDao.insertProduct(ProductEntity(name, updatedAt = now)).toInt()
        itemDao.insertItem(ItemEntity(
            productId = productId,
            quantity = quantity,
            unit = unit,
            expirationDate = expirationDate,
            addedAt = LocalDate.now(),
        ))
        productDao.touchUpdatedAt(productId, now)
        syncIfAuthenticated { userId ->
            // El push corre después, en appScope: el producto pudo haberse renombrado o borrado.
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
        val now = System.currentTimeMillis()
        val current = itemDao.getItem(itemId) ?: return
        itemDao.updateItem(
            current.copy(quantity = quantity, unit = unit, expirationDate = expirationDate).also { it.uid = itemId }
        )
        productDao.touchUpdatedAt(current.productId, now)
        syncIfAuthenticated { userId ->
            val product = productDao.findProductById(current.productId) ?: return@syncIfAuthenticated
            if (product.serverId != null) {
                val items = itemDao.getItemsByProduct(product.uid)
                val resolved = syncRepository.updateProductItems(
                    userId, product.serverId, items.map { it.toFirestoreItem() }, product.updatedAt
                )
                persistItemServerIds(items, resolved)
            }
        }
    }

    override suspend fun deleteItem(itemId: Int) {
        val now = System.currentTimeMillis()
        val item = itemDao.getItem(itemId)
        itemDao.deleteItem(itemId)
        if (item != null) productDao.touchUpdatedAt(item.productId, now)
        syncIfAuthenticated { userId ->
            item ?: return@syncIfAuthenticated
            val remainingItems = itemDao.getItemsByProduct(item.productId)
            val product = productDao.findProductById(item.productId) ?: return@syncIfAuthenticated
            if (product.serverId != null) {
                if (remainingItems.isEmpty()) {
                    syncRepository.deleteProduct(userId, product.serverId)
                } else {
                    val resolved = syncRepository.updateProductItems(
                        userId, product.serverId, remainingItems.map { it.toFirestoreItem() }, product.updatedAt
                    )
                    persistItemServerIds(remainingItems, resolved)
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
        val remoteServerIds = remoteProducts.mapTo(mutableSetOf()) { it.serverId }
        val localSynced = productDao.getProductsWithItems().first()
            .filter { it.product.serverId != null }

        for (local in localSynced) {
            if (local.product.serverId !in remoteServerIds) {
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
                val productId = productDao.insertProduct(
                    ProductEntity(name = name, serverId = winner.serverId, updatedAt = mergedUpdatedAt)
                ).toInt()
                mergedItems.forEach { itemDao.insertItem(it.toItemEntity(productId)) }
            } else {
                // Last-write-wins: skip stale remote so a newer un-synced local edit isn't clobbered.
                if (mergedUpdatedAt < local.updatedAt) continue
                productDao.updateProduct(
                    local.copy(name = name, serverId = winner.serverId, updatedAt = mergedUpdatedAt)
                        .also { it.uid = local.uid }
                )
                // Se reemplazan todos los ítems locales, sin intentar distinguir cuáles ya están en
                // Firestore: con la cola offline, un ítem sin serverId puede estar igual en el
                // servidor, y quedarse con él duplicaría la copia que trae el snapshot.
                itemDao.getItemsByProduct(local.uid).forEach { itemDao.deleteItem(it.uid) }
                mergedItems.forEach { itemDao.insertItem(it.toItemEntity(local.uid)) }
            }

            // Converge the remote too. Pushing the union before deleting the losers keeps the
            // items safe if another device processes the delete first; leaving the losers alive
            // would let every later snapshot resurrect their items.
            val losers = group.filter { it.serverId != winner.serverId }
            if (losers.isNotEmpty()) {
                syncRepository.updateProductItems(userId, winner.serverId, mergedItems, mergedUpdatedAt)
                losers.forEach { syncRepository.deleteProduct(userId, it.serverId) }
            }
        }
    }

    private suspend fun syncIfAuthenticated(block: suspend (userId: String) -> Unit) {
        val user = authRepository.currentUser.first() ?: return
        appScope.launch {
            runCatching { block(user.uid) }
                .onFailure { _syncStatus.value = SyncStatus.Error }
        }
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
