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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
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
    private val authRepository: AuthRepository,
    private val syncRepository: FirestoreSyncRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ProductRepository {

    init {
        appScope.launch {
            authRepository.currentUser.collectLatest { user ->
                if (user != null) {
                    migrateLocalDataToFirestore(user.uid)
                    syncRepository.listenToChanges(user.uid).collect { applyRemoteChanges(it) }
                } else {
                    clearLocalData()
                }
            }
        }
    }

    private suspend fun clearLocalData() {
        productDao.deleteAllProducts()
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
        val existing = productDao.findProductByName(name)
        val productId = existing?.uid ?: productDao.insertProduct(ProductEntity(name)).toInt()
        val itemServerId = UUID.randomUUID().toString()
        itemDao.insertItem(ItemEntity(
            productId = productId,
            quantity = quantity,
            unit = unit,
            expirationDate = expirationDate,
            addedAt = LocalDate.now(),
            serverId = itemServerId,
        ))
        syncIfAuthenticated { userId ->
            val product = productDao.findProductByName(name)!!
            val items = itemDao.getItemsByProduct(product.uid)
            // Assign serverId in Room BEFORE calling Firestore so the snapshot listener
            // finds the product by serverId and doesn't insert a duplicate.
            val serverId = product.serverId ?: UUID.randomUUID().toString().also { id ->
                productDao.updateServerId(product.uid, id)
            }
            syncRepository.upsertProduct(userId, product.copy(serverId = serverId), items)
        }
    }

    override suspend fun updateProduct(productId: Int, name: String) {
        productDao.updateProduct(ProductEntity(name).also { it.uid = productId })
        syncIfAuthenticated { userId ->
            val product = productDao.findProductByName(name) ?: return@syncIfAuthenticated
            if (product.serverId != null) {
                val items = itemDao.getItemsByProduct(product.uid)
                syncRepository.upsertProduct(userId, product, items)
            }
        }
    }

    override suspend fun updateItem(itemId: Int, quantity: Float, unit: ProductUnit?, expirationDate: LocalDate?) {
        val current = itemDao.getItem(itemId) ?: return
        itemDao.updateItem(
            current.copy(quantity = quantity, unit = unit, expirationDate = expirationDate).also { it.uid = itemId }
        )
        syncIfAuthenticated { userId ->
            val product = productDao.findProductByName(
                productDao.getProductsWithItems().first()
                    .find { it.items.any { i -> i.uid == itemId } }?.product?.name ?: return@syncIfAuthenticated
            ) ?: return@syncIfAuthenticated
            if (product.serverId != null) {
                val items = itemDao.getItemsByProduct(product.uid)
                syncRepository.updateProductItems(userId, product.serverId, items)
            }
        }
    }

    override suspend fun deleteItem(itemId: Int) {
        val item = itemDao.getItem(itemId)
        itemDao.deleteItem(itemId)
        syncIfAuthenticated { userId ->
            item ?: return@syncIfAuthenticated
            val remainingItems = itemDao.getItemsByProduct(item.productId)
            val product = productDao.getProductsWithItems().first()
                .find { it.product.uid == item.productId }?.product ?: return@syncIfAuthenticated
            if (product.serverId != null) {
                if (remainingItems.isEmpty()) {
                    syncRepository.deleteProduct(userId, product.serverId)
                } else {
                    syncRepository.updateProductItems(userId, product.serverId, remainingItems)
                }
            }
        }
    }

    override suspend fun deleteProduct(productId: Int) {
        val product = productDao.getProductsWithItems().first()
            .find { it.product.uid == productId }?.product
        productDao.deleteProduct(productId)
        syncIfAuthenticated { userId ->
            product?.serverId?.let { syncRepository.deleteProduct(userId, it) }
        }
    }

    private suspend fun migrateLocalDataToFirestore(userId: String) {
        val unsynced = productDao.getProductsWithoutServerId()
        for (product in unsynced) {
            val items = itemDao.getItemsByProduct(product.uid)
            val serverId = syncRepository.upsertProduct(userId, product, items)
            productDao.updateServerId(product.uid, serverId)
            items.filter { it.serverId == null }.forEach { item ->
                itemDao.updateServerId(item.uid, UUID.randomUUID().toString())
            }
        }
    }

    private suspend fun applyRemoteChanges(remoteProducts: List<FirestoreProduct>) {
        for (remote in remoteProducts) {
            val byServerId = productDao.findProductByServerId(remote.serverId)
            if (byServerId != null) continue

            val byName = productDao.findProductByName(remote.name)
            if (byName != null) {
                if (byName.serverId == null) productDao.updateServerId(byName.uid, remote.serverId)
                continue
            }

            val productId = productDao.insertProduct(
                ProductEntity(name = remote.name, serverId = remote.serverId)
            ).toInt()
            remote.items.forEach { remoteItem ->
                itemDao.insertItem(remoteItem.toItemEntity(productId))
            }
        }
    }

    private suspend fun syncIfAuthenticated(block: suspend (userId: String) -> Unit) {
        val user = authRepository.currentUser.first() ?: return
        appScope.launch { block(user.uid) }
    }
}

private fun FirestoreItem.toItemEntity(productId: Int) = ItemEntity(
    productId = productId,
    quantity = quantity,
    unit = unit?.let { runCatching { ProductUnit.valueOf(it) }.getOrNull() },
    expirationDate = expirationDate?.let { LocalDate.ofEpochDay(it) },
    addedAt = LocalDate.ofEpochDay(addedAt),
    serverId = id,
)
