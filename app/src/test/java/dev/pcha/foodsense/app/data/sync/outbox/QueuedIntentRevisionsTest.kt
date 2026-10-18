package dev.pcha.foodsense.app.data.sync.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pcha.foodsense.app.data.DefaultProductRepository
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.auth.User
import dev.pcha.foodsense.app.data.local.database.AppDatabase
import dev.pcha.foodsense.app.data.local.database.ProductUnit
import dev.pcha.foodsense.app.data.local.database.RoomLocalTransactions
import dev.pcha.foodsense.app.data.sync.FirestoreItem
import dev.pcha.foodsense.app.data.sync.FirestoreProduct
import dev.pcha.foodsense.app.data.sync.FirestoreSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two edits of the same entity queued before the first one drains. The second intent must carry the
 * revision the first one reserved, not the stale one, so the server applies both in order instead of
 * reporting the second as a conflict.
 */
@RunWith(RobolectricTestRunner::class)
class QueuedIntentRevisionsTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun repository(outbox: InventoryOutbox, accountId: String? = ACCOUNT) = DefaultProductRepository(
        productDao = db.productDao(),
        itemDao = db.itemDao(),
        authRepository = SignedInAuth(accountId),
        syncRepository = UnusedSyncRepository(),
        appScope = TestScope(),
        localTransactions = RoomLocalTransactions(db),
        intents = OutboxInventoryIntents(outbox),
    )

    private suspend fun queued(accountId: String = ACCOUNT): List<InventoryOperation> =
        db.syncOutboxDao().observe(accountId).first().map { InventoryOperation.decode(it.payload, it.payloadVersion) }

    @Test
    fun twoEditsQueuedBeforeTheFirstDrains_applyInOrderAndLandOnRevisionTwo() = runTest {
        val outbox = InventoryOutbox(db)
        val repository = repository(outbox)

        repository.addBatch("QA_Apples", 1f, null, null, count = 1)

        val product = db.productDao().findProductByName("QA_Apples")!!
        val item = db.itemDao().getItemsByProduct(product.uid).single()
        val creation = queued().single()
        assertEquals(2, creation.changes.size)
        // The stored revision already includes what the queued intent will produce, not what the server
        // holds today (nothing: the entity has never been written).
        assertEquals(1L, product.revision)
        assertEquals(1L, item.revision)

        repository.updateItems(listOf(item.uid), 2f, ProductUnit.L, null)

        val queuedAfterEdit = queued()
        assertEquals(2, queuedAfterEdit.size)
        val edit = queuedAfterEdit.last()
        // The product is left alone: this device already queued its creation, so the edit only carries
        // the item, based on the revision the creation reserved.
        val change = edit.changes.single()
        assertEquals(InventoryEntityType.ITEM, change.type)
        assertEquals(item.entityId, change.entityId)
        assertEquals(1L, change.baseRevision)
        assertEquals(JsonPrimitive(2.0), change.fields["quantity"])
        assertEquals(JsonPrimitive("L"), change.fields["unit"])
        assertEquals(2L, db.itemDao().findItemByEntityId(item.entityId)!!.revision)

        // Server side: FIFO application through the same planner the Firestore adapter uses.
        val appliedCreation = InventoryOperationProtocol.plan(creation, emptyMap())
        val appliedEdit = InventoryOperationProtocol.plan(edit, appliedCreation.writes.associateBy { it.key })
        assertEquals(1L, appliedCreation.writes.single { it.key.type == InventoryEntityType.PRODUCT }.revision)
        assertEquals(1L, appliedCreation.writes.single { it.key.type == InventoryEntityType.ITEM }.revision)
        assertEquals(2L, appliedEdit.writes.single().revision)

        // Both receipts land locally and leave nothing queued.
        outbox.acknowledge(ACCOUNT, creation.id, appliedCreation.effects)
        outbox.acknowledge(ACCOUNT, edit.id, appliedEdit.effects)
        assertEquals(2L, db.itemDao().findItemByEntityId(item.entityId)!!.revision)
        assertEquals(1L, db.productDao().findProductByEntityId(product.entityId)!!.revision)
        assertNull(db.syncOutboxDao().head(ACCOUNT))
    }

    @Test
    fun deletingAnItem_queuesATombstoneBasedOnTheRevisionTheServerKnows() = runTest {
        val outbox = InventoryOutbox(db)
        val repository = repository(outbox)
        repository.addBatch("QA_Apples", 1f, null, null, count = 2)
        val product = db.productDao().findProductByName("QA_Apples")!!
        val victim = db.itemDao().getItemsByProduct(product.uid).first()

        repository.deleteItem(victim.uid)

        val deletion = queued().last().changes.single()
        assertTrue(deletion.deleted)
        assertEquals(victim.entityId, deletion.entityId)
        assertEquals(1L, deletion.baseRevision)

        val key = InventoryEntityKey(InventoryEntityType.ITEM, victim.entityId)
        val plan = InventoryOperationProtocol.plan(
            queued().last(),
            mapOf(key to RemoteInventoryEntity(key, revision = 1, deleted = false, fields = emptyMap())),
        )
        assertEquals(2L, plan.writes.single().revision)
        assertTrue(plan.writes.single().deleted)
    }

    @Test
    fun renamingAProduct_queuesOneProductChangeBasedOnTheReservedRevision() = runTest {
        val outbox = InventoryOutbox(db)
        val repository = repository(outbox)
        repository.addBatch("QA_Apples", 1f, null, null, count = 1)
        val product = db.productDao().findProductByName("QA_Apples")!!

        repository.updateProduct(product.uid, "QA_Apples_Renamed")

        val change = queued().last().changes.single()
        assertEquals(InventoryEntityType.PRODUCT, change.type)
        assertEquals(1L, change.baseRevision)
        assertEquals(JsonPrimitive("QA_Apples_Renamed"), change.fields["name"])
        assertEquals(2L, db.productDao().findProductById(product.uid)!!.revision)
    }

    @Test
    fun anActionTooLargeForOneIntent_staysLocalAndQueuesNothing() = runTest {
        val outbox = InventoryOutbox(db)
        val repository = repository(outbox)

        repository.addBatch("QA_Huge", 1f, null, null, count = 101)

        val product = db.productDao().findProductByName("QA_Huge")!!
        assertTrue(queued().isEmpty())
        assertEquals(101, db.itemDao().getItemsByProduct(product.uid).size)
        assertEquals(0L, product.revision)
    }

    @Test
    fun signedOutDevice_queuesNothingAndLeavesLocalRevisionsAlone() = runTest {
        val outbox = InventoryOutbox(db)
        val repository = repository(outbox, accountId = null)

        repository.addBatch("QA_Local", 1f, null, null, count = 3)

        val product = db.productDao().findProductByName("QA_Local")!!
        assertTrue(queued().isEmpty())
        assertEquals(0L, product.revision)
        assertTrue(db.itemDao().getItemsByProduct(product.uid).all { it.revision == 0L })
    }

    @Test
    fun productionBinding_leavesThePathDarkUntilTheWorkerExists() = runTest {
        // The default recorder is what Hilt binds today: no device queues intents it cannot deliver.
        val repository = DefaultProductRepository(
            db.productDao(),
            db.itemDao(),
            SignedInAuth(ACCOUNT),
            UnusedSyncRepository(),
            TestScope(),
            RoomLocalTransactions(db),
        )

        repository.addBatch("QA_Dark", 1f, null, null, count = 2)

        val product = db.productDao().findProductByName("QA_Dark")!!
        assertTrue(queued().isEmpty())
        assertEquals(0L, product.revision)
    }
}

private const val ACCOUNT = "account-a"

private class SignedInAuth(uid: String?) : AuthRepository {
    private val _currentUser = MutableStateFlow(uid?.let { User(uid = it, displayName = null, email = null, photoUrl = null) })
    override val currentUser: Flow<User?> = _currentUser.asStateFlow()

    override suspend fun signInWithGoogle(context: Context): Result<User> = error("Unused")
    override suspend fun signInWithEmail(email: String, password: String): Result<User> = error("Unused")
    override suspend fun createAccountWithEmail(email: String, password: String): Result<User> = error("Unused")
    override suspend fun signOut() = Unit
}

/** The legacy sender is not under test: it is launched on the app scope, after the local commit. */
private class UnusedSyncRepository : FirestoreSyncRepository {
    override suspend fun upsertProduct(userId: String, product: FirestoreProduct): FirestoreProduct = error("Must stay offline")
    override suspend fun deleteProduct(userId: String, serverId: String) = error("Must stay offline")
    override suspend fun updateProductItems(
        userId: String,
        productServerId: String,
        items: List<FirestoreItem>,
        updatedAt: Long,
    ): List<FirestoreItem> = error("Must stay offline")
    override suspend fun fetchAll(userId: String): List<FirestoreProduct> = error("Must stay offline")
    override fun listenToChanges(userId: String): Flow<List<FirestoreProduct>> = error("Must stay offline")
}
