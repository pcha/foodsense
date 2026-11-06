package dev.pcha.foodsense.app.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.firestore.Source
import dev.pcha.foodsense.app.data.local.database.AppDatabase
import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import dev.pcha.foodsense.app.data.sync.outbox.FirebaseInventoryOperationRemote
import dev.pcha.foodsense.app.data.sync.outbox.InventoryChange
import dev.pcha.foodsense.app.data.sync.outbox.InventoryEntityType
import dev.pcha.foodsense.app.data.sync.outbox.InventoryOperation
import dev.pcha.foodsense.app.data.sync.outbox.InventoryOutbox
import dev.pcha.foodsense.app.data.sync.outbox.InventoryOutboxDrain
import dev.pcha.foodsense.app.data.sync.outbox.LocalInventoryChanges
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** End-to-end: Room outbox, the drain engine and the real Firestore adapter against the emulator. */
@RunWith(AndroidJUnit4::class)
class InventoryOutboxDrainFirestoreTest {
    private lateinit var db: AppDatabase
    private lateinit var outbox: InventoryOutbox
    private lateinit var drain: InventoryOutboxDrain
    private lateinit var session: FirebaseEmulatorSession

    @Before
    fun setUp() = firebaseEmulatorTest {
        session = FirebaseEmulatorSession.open()
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        outbox = InventoryOutbox(db)
        drain = InventoryOutboxDrain(outbox, FirebaseInventoryOperationRemote(session.firestore))
    }

    @After
    fun tearDown() = firebaseEmulatorTest {
        db.close()
        session.close()
    }

    private fun product(id: String = "p1", revision: Long = 0, name: String = "Apples") =
        InventoryChange(InventoryEntityType.PRODUCT, id, revision, mapOf("name" to JsonPrimitive(name)))

    private fun item(index: Int) = InventoryChange(InventoryEntityType.ITEM, "i$index", 0, mapOf(
        "productId" to JsonPrimitive("p1"), "quantity" to JsonPrimitive(1), "unit" to JsonNull,
        "expirationDate" to JsonNull, "addedAt" to JsonPrimitive(20744),
    ))

    private fun collection(userId: String, path: String) =
        session.firestore.collection("users").document(userId).collection(path)

    @Test
    fun drain_versionedIntents_reachFirestoreAndLeaveTheOutboxEmpty() = firebaseEmulatorTest {
        val userId = session.userId
        outbox.enqueue(userId, listOf(InventoryOperation("add15", listOf(product()) + List(15) { item(it) })), 1L) {}
        outbox.enqueue(userId, listOf(InventoryOperation("rename", listOf(product(revision = 1, name = "Pears")))), 2L) {}

        val report = drain.drain(userId)

        assertEquals(listOf("add15", "rename"), report.acknowledged)
        assertNull(report.blocked)
        assertNull(report.deferred)
        assertTrue(db.syncOutboxDao().observe(userId).first().isEmpty())

        val stored = collection(userId, FirebaseInventoryOperationRemote.PRODUCTS).document("p1")
            .get(Source.SERVER).await()
        assertEquals("Pears", stored.getString("name"))
        assertEquals(2L, stored.getLong("revision"))
        assertEquals(15, collection(userId, FirebaseInventoryOperationRemote.ITEMS).get(Source.SERVER).await().size())
    }

    @Test
    fun drain_successfulApply_advancesLocalRevisionsFromTheReceipt() = firebaseEmulatorTest {
        val userId = session.userId
        val productUid = db.productDao().insertProduct(ProductEntity(name = "Apples", entityId = "p1")).toInt()
        db.itemDao().insertItem(ItemEntity(productUid, 1f, null, null, LocalDate.now(), entityId = "i1"))
        val created = db.productDao().findProductById(productUid)!!
        val item = db.itemDao().getItemsByProduct(productUid).single()
        outbox.enqueue(userId, listOf(InventoryOperation("create", listOf(
            LocalInventoryChanges.product(created),
            LocalInventoryChanges.item(item, created.entityId),
        ))), 1L) {}

        drain.drain(userId)

        assertEquals(1L, db.productDao().findProductById(productUid)!!.revision)
        assertEquals(1L, db.itemDao().getItemsByProduct(productUid).single().revision)

        // The next edit is built from the row, so it already carries the receipt's revision as its base.
        val renamed = db.productDao().findProductById(productUid)!!.copy(name = "Pears")
        db.productDao().updateProduct(renamed)
        val change = LocalInventoryChanges.product(renamed)
        assertEquals(1L, change.baseRevision)
        outbox.enqueue(userId, listOf(InventoryOperation("rename", listOf(change))), 2L) {}

        val report = drain.drain(userId)

        assertEquals(listOf("rename"), report.acknowledged)
        assertEquals(2L, db.productDao().findProductById(productUid)!!.revision)
        val stored = collection(userId, FirebaseInventoryOperationRemote.PRODUCTS).document("p1")
            .get(Source.SERVER).await()
        assertEquals("Pears", stored.getString("name"))
        assertEquals(2L, stored.getLong("revision"))
    }

    @Test
    fun drain_acknowledgementLost_replayIsNoOpAndStillAcknowledged() = firebaseEmulatorTest {
        val userId = session.userId
        val operation = InventoryOperation("create", listOf(product()))
        outbox.enqueue(userId, listOf(operation), 1L) {}
        drain.drain(userId)

        // Simulate a crash after the server committed the receipt but before Room removed the entry.
        outbox.enqueue(userId, listOf(operation), 2L) {}
        val report = drain.drain(userId)

        assertEquals(listOf("create"), report.acknowledged)
        assertNull(db.syncOutboxDao().head(userId))
        val stored = collection(userId, FirebaseInventoryOperationRemote.PRODUCTS).document("p1")
            .get(Source.SERVER).await()
        assertEquals(1L, stored.getLong("revision"))
        assertEquals(1, collection(userId, FirebaseInventoryOperationRemote.OPERATIONS).get(Source.SERVER).await().size())
    }
}
