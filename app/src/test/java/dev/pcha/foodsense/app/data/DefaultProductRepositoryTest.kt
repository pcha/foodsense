package dev.pcha.foodsense.app.data

import android.content.Context
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.auth.User
import dev.pcha.foodsense.app.data.local.database.ItemDao
import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductDao
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import dev.pcha.foodsense.app.data.local.database.ProductUnit
import dev.pcha.foodsense.app.data.local.database.ProductWithItems
import dev.pcha.foodsense.app.data.sync.FirestoreItem
import dev.pcha.foodsense.app.data.sync.FirestoreProduct
import dev.pcha.foodsense.app.data.sync.FirestoreSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProductRepositoryTest {

    private fun makeRepository(
        syncRepository: FakeSyncRepository = FakeSyncRepository(),
    ): Triple<DefaultProductRepository, FakeItemDao, FakeSyncRepository> {
        val itemDao = FakeItemDao()
        val repo = DefaultProductRepository(
            productDao = FakeProductDao(itemDao),
            itemDao = itemDao,
            authRepository = FakeAuthRepository(),
            syncRepository = syncRepository,
            appScope = TestScope(),
        )
        return Triple(repo, itemDao, syncRepository)
    }

    @Test
    fun products_newItemSaved_itemIsReturned() = runTest {
        val (repository) = makeRepository()

        repository.add("Bread", 1f, null, LocalDate.now())

        val products = repository.products.first()
        assertEquals(1, products.size)
        assertEquals(1, products.first().items.size)
    }

    @Test
    fun products_itemUpdated_nameIsChanged() = runTest {
        val (repository) = makeRepository()
        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now())
        val productId = repository.products.first().first().uid

        repository.updateProduct(productId, "Oat Milk")

        assertEquals("Oat Milk", repository.products.first().first().name)
    }

    @Test
    fun products_itemDeleted_isNotReturned() = runTest {
        val (repository) = makeRepository()
        repository.add("Eggs", 12f, null, null)
        val itemId = repository.products.first().first().items.first().uid

        repository.deleteItem(itemId)

        assertTrue(repository.products.first().isEmpty())
    }

    @Test
    fun products_newItemWithUnit_unitIsReturned() = runTest {
        val (repository) = makeRepository()

        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now())

        assertEquals(ProductUnit.L, repository.products.first().first().items.first().unit)
    }

    @Test
    fun add_existingProduct_addsItemOnly() = runTest {
        val (repository) = makeRepository()
        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now())

        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now().plusDays(7))

        val products = repository.products.first()
        assertEquals(1, products.size)
        assertEquals(2, products.first().items.size)
    }

    @Test
    fun deleteItem_lastItem_removesProduct() = runTest {
        val (repository) = makeRepository()
        repository.add("Eggs", 12f, null, null)
        val itemId = repository.products.first().first().items.first().uid

        repository.deleteItem(itemId)

        assertTrue(repository.products.first().isEmpty())
    }

    @Test
    fun updateItem_changesQuantityAndDate() = runTest {
        val (repository) = makeRepository()
        val newDate = LocalDate.now().plusDays(3)
        repository.add("Milk", 1f, ProductUnit.L, LocalDate.now().plusDays(30))
        val itemId = repository.products.first().first().items.first().uid

        repository.updateItem(itemId, 0.5f, ProductUnit.L, newDate)

        val item = repository.products.first().first().items.first()
        assertEquals(0.5f, item.quantity)
        assertEquals(newDate, item.expirationDate)
    }

    // --- applyRemoteChanges tests ---

    @Test
    fun applyRemoteChanges_newProductInRemote_isInsertedLocally() = runTest {
        val (repository) = makeRepository()
        val remote = FirestoreProduct(
            serverId = "server-1",
            name = "Cheese",
            items = listOf(FirestoreItem("item-1", 1f, null, null, LocalDate.now().toEpochDay())),
        )

        repository.applyRemoteChanges("user-1", listOf(remote))

        val products = repository.products.first()
        assertEquals(1, products.size)
        assertEquals("Cheese", products.first().name)
    }

    @Test
    fun applyRemoteChanges_productRemovedFromRemote_isDeletedLocally() = runTest {
        val (repository) = makeRepository()
        repository.add("Cheese", 1f, null, null)
        // Simulate the product having been synced (has a serverId in Room)
        val productDao = FakeProductDao(FakeItemDao())
        val itemDao = FakeItemDao()
        val repo2 = DefaultProductRepository(productDao, itemDao, FakeAuthRepository(), FakeSyncRepository(), TestScope())
        repo2.add("Cheese", 1f, null, null)
        productDao.updateServerId(1, "server-cheese")

        // Remote snapshot no longer includes the product
        repo2.applyRemoteChanges("user-1", emptyList())

        assertTrue(repo2.products.first().isEmpty())
    }

    @Test
    fun applyRemoteChanges_existingProductNameChanged_nameIsUpdatedLocally() = runTest {
        val itemDao = FakeItemDao()
        val productDao = FakeProductDao(itemDao)
        val repository = DefaultProductRepository(productDao, itemDao, FakeAuthRepository(), FakeSyncRepository(), TestScope())
        repository.add("Milk", 1f, null, null)
        productDao.updateServerId(1, "server-milk")

        repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct(
                serverId = "server-milk",
                name = "Oat Milk",
                items = listOf(FirestoreItem("item-1", 1f, null, null, LocalDate.now().toEpochDay())),
                updatedAt = Long.MAX_VALUE,
            )
        ))

        assertEquals("Oat Milk", repository.products.first().first().name)
    }

    @Test
    fun applyRemoteChanges_productItemsChanged_itemsAreReplacedLocally() = runTest {
        val itemDao = FakeItemDao()
        val productDao = FakeProductDao(itemDao)
        val repository = DefaultProductRepository(productDao, itemDao, FakeAuthRepository(), FakeSyncRepository(), TestScope())
        repository.add("Milk", 1f, ProductUnit.L, null)
        productDao.updateServerId(1, "server-milk")
        val newDate = LocalDate.now().plusDays(14)

        repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct(
                serverId = "server-milk",
                name = "Milk",
                items = listOf(FirestoreItem("item-new", 2f, "L", newDate.toEpochDay(), LocalDate.now().toEpochDay())),
                updatedAt = Long.MAX_VALUE,
            )
        ))

        val items = repository.products.first().first().items
        assertEquals(1, items.size)
        assertEquals(2f, items.first().quantity)
        assertEquals(newDate, items.first().expirationDate)
    }

    @Test
    fun applyRemoteChanges_remoteOlderThanLocal_keepsLocalEdit() = runTest {
        val itemDao = FakeItemDao()
        val productDao = FakeProductDao(itemDao)
        val repository = DefaultProductRepository(productDao, itemDao, FakeAuthRepository(), FakeSyncRepository(), TestScope())
        repository.add("Milk", 1f, null, null) // local updatedAt = now (large)
        productDao.updateServerId(1, "server-milk")

        // Stale remote (older timestamp) must not clobber the newer local edit.
        repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct(
                serverId = "server-milk",
                name = "Oat Milk",
                items = listOf(FirestoreItem("item-1", 1f, null, null, LocalDate.now().toEpochDay())),
                updatedAt = 1L,
            )
        ))

        assertEquals("Milk", repository.products.first().first().name)
    }

    @Test
    fun applyRemoteChanges_remoteNewerThanLocal_appliesRemote() = runTest {
        val itemDao = FakeItemDao()
        val productDao = FakeProductDao(itemDao)
        val repository = DefaultProductRepository(productDao, itemDao, FakeAuthRepository(), FakeSyncRepository(), TestScope())
        repository.add("Milk", 1f, null, null)
        productDao.updateServerId(1, "server-milk")

        repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct(
                serverId = "server-milk",
                name = "Oat Milk",
                items = listOf(FirestoreItem("item-1", 1f, null, null, LocalDate.now().toEpochDay())),
                updatedAt = Long.MAX_VALUE,
            )
        ))

        assertEquals("Oat Milk", repository.products.first().first().name)
    }

    // --- applyRemoteChanges: merge of same-named products created on different devices ---

    @Test
    fun applyRemoteChanges_sameNameFromTwoDevices_mergesItemsIntoOneProduct() = runTest {
        val fixture = Fixture()
        val today = LocalDate.now().toEpochDay()

        fixture.repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct("doc-a", "Milk", listOf(FirestoreItem("item-a", 1f, null, null, today)), 5L),
            FirestoreProduct("doc-b", "Milk", listOf(FirestoreItem("item-b", 2f, null, null, today)), 7L),
        ))

        val products = fixture.repository.products.first()
        assertEquals(1, products.size)
        assertEquals(listOf(1f, 2f), products.single().items.map { it.quantity }.sorted())
    }

    @Test
    fun applyRemoteChanges_sameNameFromTwoDevices_keepsLowestServerIdAndDeletesLoserRemotely() = runTest {
        val fixture = Fixture()
        val today = LocalDate.now().toEpochDay()

        fixture.repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct("doc-b", "Milk", listOf(FirestoreItem("item-b", 2f, null, null, today)), 7L),
            FirestoreProduct("doc-a", "Milk", listOf(FirestoreItem("item-a", 1f, null, null, today)), 5L),
        ))

        assertEquals("doc-a", fixture.productDao.findProductByName("Milk")!!.serverId)
        assertEquals(listOf("doc-b"), fixture.syncRepository.deletedProducts)
        val (target, items, updatedAt) = fixture.syncRepository.itemUpdates.single()
        assertEquals("doc-a", target)
        assertEquals(setOf("item-a", "item-b"), items.map { it.id }.toSet())
        assertEquals(7L, updatedAt)
    }

    @Test
    fun applyRemoteChanges_sameNameFromTwoDevices_bothDevicesConvergeToSameState() = runTest {
        val today = LocalDate.now()
        val snapshot = listOf(
            FirestoreProduct("doc-a", "Milk", listOf(FirestoreItem("item-a", 1f, null, null, today.toEpochDay())), 5L),
            FirestoreProduct("doc-b", "Milk", listOf(FirestoreItem("item-b", 2f, null, null, today.toEpochDay())), 7L),
        )
        // Each device starts pointing at the doc it created itself.
        val deviceA = Fixture().apply {
            val uid = productDao.insertProduct(ProductEntity("Milk", "doc-a", 1L)).toInt()
            itemDao.insertItem(ItemEntity(uid, 1f, null, null, today, "item-a"))
        }
        val deviceB = Fixture().apply {
            val uid = productDao.insertProduct(ProductEntity("Milk", "doc-b", 1L)).toInt()
            itemDao.insertItem(ItemEntity(uid, 2f, null, null, today, "item-b"))
        }

        deviceA.repository.applyRemoteChanges("user-1", snapshot)
        deviceB.repository.applyRemoteChanges("user-1", snapshot)

        val onA = deviceA.repository.products.first().single()
        val onB = deviceB.repository.products.first().single()
        assertEquals(onA.items.map { it.quantity }.sorted(), onB.items.map { it.quantity }.sorted())
        assertEquals(listOf(1f, 2f), onA.items.map { it.quantity }.sorted())
        assertEquals("doc-a", deviceA.productDao.findProductByName("Milk")!!.serverId)
        assertEquals("doc-a", deviceB.productDao.findProductByName("Milk")!!.serverId)
    }

    @Test
    fun applyRemoteChanges_localOnlyProductMatchingRemoteName_appliesRemoteItems() = runTest {
        val fixture = Fixture()
        val today = LocalDate.now()
        val uid = fixture.productDao.insertProduct(ProductEntity("Milk", null, 1L)).toInt()
        fixture.itemDao.insertItem(ItemEntity(uid, 1f, null, null, today, null))

        fixture.repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct("doc-a", "Milk", listOf(FirestoreItem("item-a", 3f, null, null, today.toEpochDay())), 5L)
        ))

        assertEquals("doc-a", fixture.productDao.findProductByName("Milk")!!.serverId)
        assertEquals(listOf(3f), fixture.repository.products.first().single().items.map { it.quantity })
    }

    @Test
    fun applyRemoteChanges_appliedTwice_isIdempotent() = runTest {
        val fixture = Fixture()
        val today = LocalDate.now().toEpochDay()
        fixture.repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct("doc-a", "Milk", listOf(FirestoreItem("item-a", 1f, null, null, today)), 5L),
            FirestoreProduct("doc-b", "Milk", listOf(FirestoreItem("item-b", 2f, null, null, today)), 7L),
        ))
        val afterFirst = fixture.repository.products.first().single().items.map { it.quantity }.sorted()
        fixture.syncRepository.deletedProducts.clear()
        fixture.syncRepository.itemUpdates.clear()

        // What Firestore holds after the merge: one doc carrying both items.
        fixture.repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct("doc-a", "Milk", listOf(
                FirestoreItem("item-a", 1f, null, null, today),
                FirestoreItem("item-b", 2f, null, null, today),
            ), 7L)
        ))

        assertEquals(afterFirst, fixture.repository.products.first().single().items.map { it.quantity }.sorted())
        assertTrue(fixture.syncRepository.deletedProducts.isEmpty())
        assertTrue(fixture.syncRepository.itemUpdates.isEmpty())
    }

    @Test
    fun applyRemoteChanges_duplicateNamesButStaleRemote_keepsLocalEditAndSkipsRemoteCleanup() = runTest {
        val fixture = Fixture()
        val today = LocalDate.now()
        val uid = fixture.productDao.insertProduct(ProductEntity("Milk", "doc-a", Long.MAX_VALUE)).toInt()
        fixture.itemDao.insertItem(ItemEntity(uid, 9f, null, null, today, "item-local"))

        fixture.repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct("doc-a", "Milk", listOf(FirestoreItem("item-a", 1f, null, null, today.toEpochDay())), 1L),
            FirestoreProduct("doc-b", "Milk", listOf(FirestoreItem("item-b", 2f, null, null, today.toEpochDay())), 2L),
        ))

        assertEquals(listOf(9f), fixture.repository.products.first().single().items.map { it.quantity })
        assertTrue(fixture.syncRepository.deletedProducts.isEmpty())
        assertTrue(fixture.syncRepository.itemUpdates.isEmpty())
    }

    @Test
    fun add_newProduct_bumpsProductUpdatedAt() = runTest {
        val itemDao = FakeItemDao()
        val productDao = FakeProductDao(itemDao)
        val repository = DefaultProductRepository(productDao, itemDao, FakeAuthRepository(), FakeSyncRepository(), TestScope())

        repository.add("Milk", 1f, null, null)

        assertTrue(productDao.findProductById(1)!!.updatedAt > 0L)
    }

    @Test
    fun updateItem_bumpsProductUpdatedAt() = runTest {
        val itemDao = FakeItemDao()
        val productDao = FakeProductDao(itemDao)
        val repository = DefaultProductRepository(productDao, itemDao, FakeAuthRepository(), FakeSyncRepository(), TestScope())
        repository.add("Milk", 1f, null, null)
        productDao.touchUpdatedAt(1, 1L) // reset to a known-old value
        val itemId = repository.products.first().first().items.first().uid

        repository.updateItem(itemId, 2f, null, null)

        assertTrue(productDao.findProductById(1)!!.updatedAt > 1L)
    }

    @Test
    fun clearLocalData_preservesLocalOnlyProducts_deletesSynced() = runTest {
        val itemDao = FakeItemDao()
        val productDao = FakeProductDao(itemDao)
        val repository = DefaultProductRepository(productDao, itemDao, FakeAuthRepository(), FakeSyncRepository(), TestScope())
        repository.add("Synced", 1f, null, null)     // uid 1
        repository.add("LocalOnly", 1f, null, null)   // uid 2
        productDao.updateServerId(1, "server-1")

        repository.clearLocalData()

        val remaining = repository.products.first()
        assertEquals(1, remaining.size)
        assertEquals("LocalOnly", remaining.first().name)
    }

    // --- Sync orchestration: login/logout wiring, sync(), and the push side of each mutation ---

    @Test
    fun login_localOnlyProducts_areUploadedAndGetServerIds() = runTest {
        val fixture = Fixture(appScope = backgroundScope)
        val uid = fixture.productDao.insertProduct(ProductEntity("Milk", null, 1L)).toInt()
        fixture.itemDao.insertItem(ItemEntity(uid, 1f, null, null, LocalDate.now(), null))

        fixture.authRepository.signIn()
        runCurrent()

        assertNotNull(fixture.productDao.findProductByName("Milk")!!.serverId)
        assertNotNull(fixture.itemDao.getItemsByProduct(uid).single().serverId)
        assertEquals(1, fixture.syncRepository.upserts.size)
    }

    @Test
    fun logout_deletesSyncedProductsAndKeepsLocalOnly() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        runCurrent()
        val synced = fixture.productDao.insertProduct(ProductEntity("Synced", "doc-a", 1L)).toInt()
        fixture.itemDao.insertItem(ItemEntity(synced, 1f, null, null, LocalDate.now(), "i1"))
        val localOnly = fixture.productDao.insertProduct(ProductEntity("LocalOnly", null, 1L)).toInt()
        fixture.itemDao.insertItem(ItemEntity(localOnly, 1f, null, null, LocalDate.now(), null))

        fixture.authRepository.signOut()
        runCurrent()

        assertEquals(listOf("LocalOnly"), fixture.repository.products.first().map { it.name })
    }

    @Test
    fun sync_remoteReachable_appliesRemoteAndReportsIdle() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn())
        fixture.syncRepository.remote.value = listOf(
            FirestoreProduct("doc-a", "Milk", listOf(FirestoreItem("i1", 1f, null, null, LocalDate.now().toEpochDay())), 5L)
        )

        assertTrue(fixture.repository.sync())

        assertEquals("Milk", fixture.repository.products.first().single().name)
        assertEquals(SyncStatus.Idle, fixture.repository.syncStatus.first())
    }

    @Test
    fun sync_remoteRejects_reportsErrorAndReturnsFalse() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn())
        fixture.syncRepository.failure = IllegalStateException("PERMISSION_DENIED")

        assertFalse(fixture.repository.sync())

        assertEquals(SyncStatus.Error, fixture.repository.syncStatus.first())
    }

    @Test
    fun add_authenticated_upsertsProductAndStoresServerIds() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)

        fixture.repository.add("Milk", 1f, ProductUnit.L, null)
        runCurrent()

        val product = fixture.productDao.findProductByName("Milk")!!
        assertNotNull(product.serverId)
        assertNotNull(fixture.itemDao.getItemsByProduct(product.uid).single().serverId)
    }

    @Test
    fun updateItem_syncedProduct_pushesTheItemList() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        fixture.repository.add("Milk", 1f, null, null)
        runCurrent()
        val itemId = fixture.repository.products.first().single().items.single().uid
        fixture.syncRepository.itemUpdates.clear()

        fixture.repository.updateItem(itemId, 2f, null, null)
        runCurrent()

        val (serverId, items, _) = fixture.syncRepository.itemUpdates.single()
        assertEquals(fixture.productDao.findProductByName("Milk")!!.serverId, serverId)
        assertEquals(listOf(2f), items.map { it.quantity })
    }

    @Test
    fun deleteItem_lastItemOfSyncedProduct_deletesTheRemoteDoc() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        fixture.repository.add("Milk", 1f, null, null)
        runCurrent()
        val serverId = fixture.productDao.findProductByName("Milk")!!.serverId
        val itemId = fixture.repository.products.first().single().items.single().uid

        fixture.repository.deleteItem(itemId)
        runCurrent()

        assertEquals(listOf(serverId), fixture.syncRepository.deletedProducts)
    }

    @Test
    fun deleteProduct_syncedProduct_deletesTheRemoteDoc() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        fixture.repository.add("Milk", 1f, null, null)
        runCurrent()
        val product = fixture.productDao.findProductByName("Milk")!!

        fixture.repository.deleteProduct(product.uid)
        runCurrent()

        assertEquals(listOf(product.serverId), fixture.syncRepository.deletedProducts)
    }

    @Test
    fun add_remotePushRejected_keepsLocalWriteAndReportsError() = runTest {
        // The exact shape of the outage that started this: Firestore refuses the write, so the
        // product never gets a serverId, but the user's data must still be in Room.
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        runCurrent() // dejar que el login se asiente antes de simular la caída
        fixture.syncRepository.failure = IllegalStateException("PERMISSION_DENIED")

        fixture.repository.add("Milk", 1f, null, null)
        runCurrent()

        assertEquals("Milk", fixture.repository.products.first().single().name)
        assertNull(fixture.productDao.findProductByName("Milk")!!.serverId)
        assertEquals(SyncStatus.Error, fixture.repository.syncStatus.first())
    }

    @Test
    fun add_productRenamedBeforePushRuns_doesNotReportError() = runTest {
        // add() schedules its push on appScope and returns, so a rename can land first. Looking
        // the product up by its old name then finds nothing.
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        runCurrent() // dejar que el login se asiente para que el listener no pise el estado después

        // Nada de collectar `products` en el medio: eso cedería el dispatcher y dejaría correr el
        // push encolado antes del rename, que es justo la carrera que se quiere reproducir.
        fixture.repository.add("Milk", 1f, null, null)
        val productId = fixture.productDao.findProductByName("Milk")!!.uid
        fixture.repository.updateProduct(productId, "Oat Milk")
        runCurrent()

        assertEquals(SyncStatus.Idle, fixture.repository.syncStatus.first())
    }

    // --- serverId != null on an item must mean "confirmed in Firestore" ---

    @Test
    fun add_secondItemToSyncedProduct_storesItemServerId() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        fixture.repository.add("Milk", 1f, null, null)
        runCurrent()

        fixture.repository.add("Milk", 2f, null, null) // el producto ya tiene serverId
        runCurrent()

        val items = fixture.itemDao.getItemsByProduct(fixture.productDao.findProductByName("Milk")!!.uid)
        assertEquals(2, items.size)
        assertTrue("todo ítem pusheado debe conocer su id", items.all { it.serverId != null })
    }

    @Test
    fun applyRemoteChanges_afterAddingSecondItem_doesNotDuplicateItems() = runTest {
        // La regresión: el segundo ítem se sincroniza bien, pero si Room no aprende su id, el
        // snapshot lo reinserta como si fuera otro ítem distinto.
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        fixture.repository.add("Milk", 1f, null, null)
        runCurrent()
        fixture.repository.add("Milk", 2f, null, null)
        runCurrent()

        fixture.repository.applyRemoteChanges("user-1", fixture.syncRepository.remote.value)

        assertEquals(listOf(1f, 2f), fixture.repository.products.first().single().items.map { it.quantity }.sorted())
    }

    @Test
    fun updateItem_pushedChange_leavesEveryItemWithAServerId() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        fixture.repository.add("Milk", 1f, null, null)
        runCurrent()
        val productId = fixture.productDao.findProductByName("Milk")!!.uid
        val itemId = fixture.itemDao.getItemsByProduct(productId).single().uid

        fixture.repository.updateItem(itemId, 3f, null, null)
        runCurrent()

        // Por uid no sirve: al aplicar el snapshot los ítems se reinsertan y cambian de uid.
        val items = fixture.itemDao.getItemsByProduct(productId)
        assertEquals(listOf(3f), items.map { it.quantity })
        assertTrue(items.all { it.serverId != null })
    }

    @Test
    fun deleteItem_remainingItems_keepTheirServerIds() = runTest {
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        fixture.repository.add("Milk", 1f, null, null)
        runCurrent()
        fixture.repository.add("Milk", 2f, null, null)
        runCurrent()
        val productId = fixture.productDao.findProductByName("Milk")!!.uid
        val firstItem = fixture.itemDao.getItemsByProduct(productId).first().uid

        fixture.repository.deleteItem(firstItem)
        runCurrent()

        val remaining = fixture.itemDao.getItemsByProduct(productId)
        assertTrue(remaining.isNotEmpty())
        assertTrue(remaining.all { it.serverId != null })
    }

    @Test
    fun applyRemoteChanges_afterFullSyncCycle_deletesRowWhenDocDisappears() = runTest {
        // Con los ids ya confiables, un producto enteramente sincronizado se borra de verdad en
        // vez de resucitar como local-only.
        val fixture = Fixture(FakeAuthRepository.signedIn(), backgroundScope)
        fixture.repository.add("Milk", 1f, null, null)
        runCurrent()
        fixture.repository.add("Milk", 2f, null, null)
        runCurrent()

        fixture.repository.applyRemoteChanges("user-1", emptyList())

        assertNull(fixture.productDao.findProductByName("Milk"))
    }

    @Test
    fun applyRemoteChanges_offlineAddedProductAlreadyInFirestore_isNotDuplicated() = runTest {
        // Sin conexión la escritura se encola en Firestore pero el await nunca vuelve, así que Room
        // nunca aprende el serverId del ítem. Al reconectar el doc llega en el snapshot con ese
        // mismo ítem: no debe quedar una segunda copia.
        val fixture = Fixture()
        val today = LocalDate.now()
        val uid = fixture.productDao.insertProduct(ProductEntity("Leche", null, 100L)).toInt()
        fixture.itemDao.insertItem(ItemEntity(uid, 1f, null, null, today, null))

        fixture.repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct("doc-a", "Leche", listOf(FirestoreItem("i1", 1f, null, null, today.toEpochDay())), 100L)
        ))

        assertEquals(listOf(1f), fixture.repository.products.first().single().items.map { it.quantity })
    }


    @Test
    fun applyRemoteChanges_docDeletedRemotely_deletesTheRowLocally() = runTest {
        val fixture = Fixture()
        val uid = fixture.productDao.insertProduct(ProductEntity("Milk", "doc-a", 1L)).toInt()
        fixture.itemDao.insertItem(ItemEntity(uid, 1f, null, null, LocalDate.now(), "i1"))

        fixture.repository.applyRemoteChanges("user-1", emptyList())

        assertNull(fixture.productDao.findProductByName("Milk"))
    }

    @Test
    fun applyRemoteChanges_newerRemote_replacesLocalItems() = runTest {
        val fixture = Fixture()
        val today = LocalDate.now()
        val uid = fixture.productDao.insertProduct(ProductEntity("Milk", "doc-a", 100L)).toInt()
        fixture.itemDao.insertItem(ItemEntity(uid, 1f, null, null, today, "i1"))

        // LWW: el remoto es más nuevo, así que su lista de ítems reemplaza la local entera.
        fixture.repository.applyRemoteChanges("user-1", listOf(
            FirestoreProduct("doc-a", "Milk", listOf(FirestoreItem("i1", 2f, null, null, today.toEpochDay())), 200L)
        ))

        assertEquals(listOf(2f), fixture.itemDao.getItemsByProduct(uid).map { it.quantity })
    }
}

/**
 * One device's worth of collaborators, so a test can seed Room and assert on the remote calls.
 *
 * `appScope` defaults to a detached [TestScope], which parks the repository's init block without
 * ever running it — that suits tests that drive `applyRemoteChanges` and friends directly. Pass
 * the test's own `backgroundScope` to exercise the login/logout wiring for real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class Fixture(
    val authRepository: FakeAuthRepository = FakeAuthRepository(),
    appScope: CoroutineScope = TestScope(),
) {
    val itemDao = FakeItemDao()
    val productDao = FakeProductDao(itemDao)
    val syncRepository = FakeSyncRepository()
    val repository = DefaultProductRepository(
        productDao = productDao,
        itemDao = itemDao,
        authRepository = authRepository,
        syncRepository = syncRepository,
        appScope = appScope,
    )
}

private class FakeItemDao : ItemDao {
    private val _items = MutableStateFlow<List<ItemEntity>>(emptyList())
    val items: StateFlow<List<ItemEntity>> = _items.asStateFlow()
    private var nextUid = 1

    override suspend fun insertItem(item: ItemEntity) {
        _items.value = _items.value + item.also { it.uid = nextUid++ }
    }

    override suspend fun updateItem(item: ItemEntity) {
        _items.value = _items.value.map { if (it.uid == item.uid) item else it }
    }

    override suspend fun getItem(uid: Int): ItemEntity? = _items.value.find { it.uid == uid }

    override suspend fun deleteItem(uid: Int) {
        _items.value = _items.value.filter { it.uid != uid }
    }

    override suspend fun getProductNamesExpiringOn(epochDay: Long): List<String> = emptyList()

    override suspend fun updateServerId(uid: Int, serverId: String) {
        _items.value = _items.value.map { if (it.uid == uid) it.copy(serverId = serverId) else it }
    }

    override suspend fun getItemsByProduct(productId: Int): List<ItemEntity> =
        _items.value.filter { it.productId == productId }

    /** Mirrors Room's ForeignKey.CASCADE; see ProductDaoTest.deleteProduct_productWithItems_cascadesToItems. */
    fun deleteItemsByProduct(productId: Int) {
        _items.value = _items.value.filter { it.productId != productId }
    }
}

private class FakeProductDao(private val itemDao: FakeItemDao) : ProductDao {
    private val _products = MutableStateFlow<List<ProductEntity>>(emptyList())
    private var nextUid = 1

    override fun getProductsWithItems(): Flow<List<ProductWithItems>> =
        combine(_products, itemDao.items) { products, items ->
            products.map { p -> ProductWithItems(p, items.filter { it.productId == p.uid }) }
        }

    override suspend fun insertProduct(product: ProductEntity): Long {
        val uid = nextUid++.toLong()
        _products.value = _products.value + product.also { it.uid = uid.toInt() }
        return uid
    }

    override suspend fun updateProduct(product: ProductEntity) {
        _products.value = _products.value.map { if (it.uid == product.uid) product else it }
    }

    override suspend fun findProductByName(name: String): ProductEntity? =
        _products.value.find { it.name == name }

    override suspend fun findProductById(uid: Int): ProductEntity? =
        _products.value.find { it.uid == uid }

    override suspend fun deleteProductsWithNoItems() {
        val productIds = itemDao.items.value.map { it.productId }.toSet()
        _products.value = _products.value.filter { it.uid in productIds }
    }

    override suspend fun deleteProduct(uid: Int) {
        _products.value = _products.value.filter { it.uid != uid }
        itemDao.deleteItemsByProduct(uid)
    }

    override fun getAllProductNames(): Flow<List<String>> =
        _products.map { it.map { p -> p.name }.sorted() }

    override suspend fun getProductsWithoutServerId(): List<ProductEntity> =
        _products.value.filter { it.serverId == null }

    override suspend fun updateServerId(uid: Int, serverId: String) {
        _products.value = _products.value.map {
            if (it.uid == uid) it.copy(serverId = serverId).also { c -> c.uid = uid } else it
        }
    }

    override suspend fun touchUpdatedAt(uid: Int, updatedAt: Long) {
        _products.value = _products.value.map {
            if (it.uid == uid) it.copy(updatedAt = updatedAt).also { c -> c.uid = uid } else it
        }
    }

    override suspend fun findProductByServerId(serverId: String): ProductEntity? =
        _products.value.find { it.serverId == serverId }

    override suspend fun deleteAllProducts() {
        _products.value = emptyList()
    }

    override suspend fun deleteSyncedProducts() {
        _products.value = _products.value.filter { it.serverId == null }
    }
}

private class FakeAuthRepository(initialUser: User? = null) : AuthRepository {
    private val _currentUser = MutableStateFlow(initialUser)
    override val currentUser: Flow<User?> = _currentUser.asStateFlow()

    fun signIn(uid: String = TEST_UID) {
        _currentUser.value = User(uid = uid, displayName = null, email = null, photoUrl = null)
    }

    override suspend fun signInWithGoogle(context: Context): Result<User> = throw NotImplementedError()
    override suspend fun signInWithEmail(email: String, password: String): Result<User> = throw NotImplementedError()
    override suspend fun createAccountWithEmail(email: String, password: String): Result<User> = throw NotImplementedError()

    override suspend fun signOut() {
        _currentUser.value = null
    }

    companion object {
        const val TEST_UID = "user-1"
        fun signedIn(uid: String = TEST_UID) =
            FakeAuthRepository(User(uid = uid, displayName = null, email = null, photoUrl = null))
    }
}

/**
 * In-memory stand-in for Firestore. Writes land in [remote], so `listenToChanges` sees what was
 * just written — the same way a real snapshot listener does. A fake that only recorded calls would
 * let the listener observe an empty backend and delete rows that had just been uploaded.
 */
private class FakeSyncRepository : FirestoreSyncRepository {
    val deletedProducts = mutableListOf<String>()
    val itemUpdates = mutableListOf<Triple<String, List<FirestoreItem>, Long>>()
    val upserts = mutableListOf<FirestoreProduct>()

    /** The backend's contents: what fetchAll returns and what listenToChanges emits. */
    val remote = MutableStateFlow<List<FirestoreProduct>>(emptyList())

    /** When set, every remote call throws it — stands in for Firestore rejecting the operation. */
    var failure: Exception? = null

    private var nextId = 0

    override suspend fun upsertProduct(userId: String, product: FirestoreProduct): FirestoreProduct {
        failure?.let { throw it }
        upserts += product
        val resolved = product.copy(
            serverId = product.serverId.ifBlank { "doc-${++nextId}" },
            items = product.items.map { it.copy(id = it.id.ifBlank { "item-${++nextId}" }) },
        )
        remote.value = remote.value.filterNot { it.serverId == resolved.serverId } + resolved
        return resolved
    }

    override suspend fun deleteProduct(userId: String, serverId: String) {
        failure?.let { throw it }
        deletedProducts += serverId
        remote.value = remote.value.filterNot { it.serverId == serverId }
    }

    override suspend fun updateProductItems(userId: String, productServerId: String, items: List<FirestoreItem>, updatedAt: Long): List<FirestoreItem> {
        failure?.let { throw it }
        val resolved = items.map { it.copy(id = it.id.ifBlank { "item-${++nextId}" }) }
        itemUpdates += Triple(productServerId, resolved, updatedAt)
        remote.value = remote.value.map {
            if (it.serverId == productServerId) it.copy(items = resolved, updatedAt = updatedAt) else it
        }
        return resolved
    }

    override suspend fun fetchAll(userId: String): List<FirestoreProduct> {
        failure?.let { throw it }
        return remote.value
    }

    override fun listenToChanges(userId: String): Flow<List<FirestoreProduct>> = remote
}
