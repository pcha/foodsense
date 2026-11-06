package dev.pcha.foodsense.app.data.sync.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pcha.foodsense.app.data.DefaultProductRepository
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.auth.User
import dev.pcha.foodsense.app.data.local.database.AppDatabase
import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import dev.pcha.foodsense.app.data.local.database.RoomLocalTransactions
import dev.pcha.foodsense.app.data.sync.FirestoreItem
import dev.pcha.foodsense.app.data.sync.FirestoreProduct
import dev.pcha.foodsense.app.data.sync.FirestoreSyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Room holds one account's inventory. When the device stops belonging to that account - sign-out, or a
 * different account signing in - its synced rows and its pending intents have to leave together, or the
 * next account reads somebody else's data and V2 delivers changes on its behalf.
 */
@RunWith(RobolectricTestRunner::class)
class LocalDataOwnershipTest {
    private lateinit var db: AppDatabase
    private val auth = SwitchableAuth()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun repository(outbox: InventoryOutbox = InventoryOutbox(db)) = DefaultProductRepository(
        productDao = db.productDao(),
        itemDao = db.itemDao(),
        authRepository = auth,
        syncRepository = PassiveSyncRepository(),
        appScope = TestScope(),
        localTransactions = RoomLocalTransactions(db),
        intents = OutboxInventoryIntents(outbox),
    )

    private fun signIn(accountId: String) {
        auth.user.value = User(uid = accountId, displayName = null, email = null, photoUrl = null)
    }

    private suspend fun insertProduct(name: String, serverId: String? = null): Int {
        val uid = db.productDao().insertProduct(ProductEntity(name = name, updatedAt = 1L)).toInt()
        db.itemDao().insertItem(ItemEntity(uid, 1f, null, null, LocalDate.now()))
        serverId?.let { db.productDao().updateServerId(uid, it) }
        return uid
    }

    @Test
    fun theFirstEmissionOfTheProcess_keepsTheRowsItOpened() = runTest {
        // A process that starts signed in must not purge: that database belongs to this user, and
        // purging would delete their offline inventory on every launch.
        val product = insertProduct("Milk", serverId = "server-1")
        val repository = repository()
        signIn(ACCOUNT)

        assertFalse(repository.handOverLocalData(auth.user.value))

        assertEquals("Milk", db.productDao().findProductById(product)?.name)
        assertEquals(1, db.itemDao().getItemsByProduct(product).size)
    }

    @Test
    fun aProcessThatStartsSignedOut_dropsWhatAKilledSessionLeftBehind() = runTest {
        val product = insertProduct("Milk", serverId = "server-1")
        InventoryOutbox(db).enqueue(ACCOUNT, listOf(leavingOperation()), 1L) {}

        val repository = repository()

        assertTrue(repository.handOverLocalData(null))

        assertNull(db.productDao().findProductById(product))
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }

    @Test
    fun signingOut_dropsTheAccountRowsAndTheirPendingIntents() = runTest {
        signIn(ACCOUNT)
        val repository = repository()
        repository.handOverLocalData(auth.user.value)
        repository.addBatch("QA_Synced", 1f, null, null, count = 2)
        repository.addBatch("QA_Offline", 1f, null, null, count = 1)
        val synced = db.productDao().findProductByName("QA_Synced")!!
        val offline = db.productDao().findProductByName("QA_Offline")!!
        db.productDao().updateServerId(synced.uid, "server-1")
        assertEquals(2, db.syncOutboxDao().getAll().size)

        auth.user.value = null

        assertTrue(repository.handOverLocalData(null))
        assertNull(db.productDao().findProductById(synced.uid))
        assertTrue(db.itemDao().getItemsByProduct(synced.uid).isEmpty())
        // A product added offline survives, but not the revision its discarded intent reserved: it goes
        // back to "not on the V2 server yet" so its next intent is a creation, not a conflicted edit.
        assertEquals("QA_Offline", db.productDao().findProductById(offline.uid)?.name)
        assertEquals(0L, db.productDao().findProductById(offline.uid)!!.revision)
        assertTrue(db.itemDao().getItemsByProduct(offline.uid).all { it.revision == 0L })
        assertTrue(db.syncOutboxDao().getAll().isEmpty())
    }

    @Test
    fun switchingAccounts_doesNotLeaveTheOldAccountInventoryVisible() = runTest {
        signIn(ACCOUNT)
        val repository = repository()
        repository.handOverLocalData(auth.user.value)
        repository.addBatch("QA_Old", 1f, null, null, count = 1)
        val old = db.productDao().findProductByName("QA_Old")!!
        db.productDao().updateServerId(old.uid, "server-old")
        repository.addBatch("QA_LocalOnly", 1f, null, null, count = 1)
        assertEquals(2, db.syncOutboxDao().getAll().size)

        signIn(OTHER_ACCOUNT)

        assertTrue(repository.handOverLocalData(auth.user.value))
        assertNull(db.productDao().findProductById(old.uid))
        assertEquals(0L, db.productDao().findProductByName("QA_LocalOnly")!!.revision)
        assertTrue(db.syncOutboxDao().getAll().isEmpty())

        // The new account's own work is queued under its own id, not the previous one.
        repository.addBatch("QA_New", 1f, null, null, count = 1)

        assertEquals(listOf(OTHER_ACCOUNT), db.syncOutboxDao().getAll().map { it.accountId }.distinct())
    }

    @Test
    fun discardingPending_removesBlockedIntentsToo() = runTest {
        val outbox = InventoryOutbox(db)
        signIn(ACCOUNT)
        repository(outbox).apply {
            handOverLocalData(auth.user.value)
            addBatch("QA_Apples", 1f, null, null, count = 1)
        }
        val intent = queued().single()
        outbox.block(ACCOUNT, intent.id, "REVISION_MISMATCH")

        outbox.discardPending()

        assertNull(db.syncOutboxDao().head(ACCOUNT))
    }

    @Test
    fun discardingPending_leavesRowsWithoutPendingIntentsAlone() = runTest {
        val product = insertProduct("Milk", serverId = "server-1")
        db.productDao().advanceRevision(product, 5L)

        InventoryOutbox(db).discardPending()

        assertEquals(5L, db.productDao().findProductById(product)!!.revision)
    }

    @Test
    fun aReceiptThatArrivesAfterItsAccountWasDiscarded_recordsTheServerRevision() = runTest {
        val outbox = InventoryOutbox(db)
        signIn(ACCOUNT)
        val repository = repository(outbox)
        repository.handOverLocalData(auth.user.value)
        repository.addBatch("QA_Apples", 1f, null, null, count = 1)
        val intent = queued().single()
        val plan = InventoryOperationProtocol.plan(intent, emptyMap())
        outbox.discardPending()

        // The drain may still be in flight when the account signs out. The server write happened, so the
        // receipt is still the truth for a row that survived the purge; it must not throw.
        outbox.acknowledge(ACCOUNT, intent.id, plan.effects)

        assertEquals(1L, db.productDao().findProductByName("QA_Apples")!!.revision)
        assertNull(db.syncOutboxDao().head(ACCOUNT))
    }

    private suspend fun queued(): List<InventoryOperation> =
        db.syncOutboxDao().observe(ACCOUNT).first().map { InventoryOperation.decode(it.payload, it.payloadVersion) }
}

private const val ACCOUNT = "account-a"
private const val OTHER_ACCOUNT = "account-b"

private fun leavingOperation() = InventoryOperation(
    id = "leaving",
    changes = listOf(
        InventoryChange(
            type = InventoryEntityType.PRODUCT,
            entityId = "p-leaving",
            baseRevision = 0,
            fields = mapOf("name" to kotlinx.serialization.json.JsonPrimitive("Milk")),
        ),
    ),
)

private class SwitchableAuth : AuthRepository {
    val user = MutableStateFlow<User?>(null)
    override val currentUser: Flow<User?> = user.asStateFlow()

    override suspend fun signInWithGoogle(context: Context): Result<User> = error("Unused")
    override suspend fun signInWithEmail(email: String, password: String): Result<User> = error("Unused")
    override suspend fun createAccountWithEmail(email: String, password: String): Result<User> = error("Unused")
    override suspend fun signOut() = Unit
}

/** The legacy sender is not under test. It listens to nothing and uploads nothing. */
private class PassiveSyncRepository : FirestoreSyncRepository {
    override suspend fun upsertProduct(userId: String, product: FirestoreProduct): FirestoreProduct = product
    override suspend fun deleteProduct(userId: String, serverId: String) = Unit
    override suspend fun updateProductItems(
        userId: String,
        productServerId: String,
        items: List<FirestoreItem>,
        updatedAt: Long,
    ): List<FirestoreItem> = items
    override suspend fun fetchAll(userId: String): List<FirestoreProduct> = emptyList()
    override fun listenToChanges(userId: String): Flow<List<FirestoreProduct>> = emptyFlow()
}
