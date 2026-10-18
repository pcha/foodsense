package dev.pcha.foodsense.app.data.sync.outbox

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pcha.foodsense.app.data.local.database.AppDatabase
import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import dev.pcha.foodsense.app.data.local.database.SyncOutboxEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class InventoryOutboxTest {
    private lateinit var db: AppDatabase
    private lateinit var outbox: InventoryOutbox

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        outbox = InventoryOutbox(db)
    }

    @After
    fun tearDown() = db.close()

    private fun operation(id: String = "op-1") = InventoryOperation(id, listOf(
        InventoryChange(InventoryEntityType.ITEM, "item-1", 7, mapOf("quantity" to JsonPrimitive(6))),
    ))

    @Test
    fun enqueue_success_commitsMutationAndImmutablePayloadTogether() = runTest {
        outbox.enqueue("account-a", operation(), 123L) {
            db.productDao().insertProduct(ProductEntity("Milk"))
        }

        assertEquals("Milk", db.productDao().getAllProductNames().first().single())
        val entry = db.syncOutboxDao().head("account-a")!!
        assertEquals(operation(), InventoryOperation.decode(entry.payload, entry.payloadVersion))
        assertEquals(123L, entry.createdAt)
        assertEquals(0, entry.attempts)
    }

    @Test
    fun enqueue_failure_rollsBackMutationAndOutbox() = runTest {
        val result = runCatching {
            outbox.enqueue("account-a", operation(), 123L) {
                db.productDao().insertProduct(ProductEntity("Milk"))
                error("Simulated interruption")
            }
        }
        assertTrue(result.isFailure)
        assertTrue(db.productDao().getAllProductNames().first().isEmpty())
        assertNull(db.syncOutboxDao().head("account-a"))
    }

    @Test
    fun enqueue_cancellation_rollsBackBothWrites() = runTest {
        try {
            outbox.enqueue("account-a", operation(), 123L) {
                db.productDao().insertProduct(ProductEntity("Milk"))
                throw CancellationException("Simulated cancellation")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(db.productDao().getAllProductNames().first().isEmpty())
            assertNull(db.syncOutboxDao().head("account-a"))
        }
    }

    @Test
    fun enqueue_duplicateOperation_doesNotRunMutationAgain() = runTest {
        var executions = 0
        outbox.enqueue("account-a", operation(), 123L) { executions++ }
        val result = runCatching {
            outbox.enqueue("account-a", operation(), 123L) { executions++ }
        }
        assertTrue(result.isFailure)
        assertEquals(1, executions)
        assertEquals(1, db.syncOutboxDao().observe("account-a").first().size)
    }

    @Test
    fun enqueue_batchDeleteAndIntent_commitTogether() = runTest {
        val productId = db.productDao().insertProduct(ProductEntity("Milk")).toInt()
        repeat(15) {
            db.itemDao().insertItem(ItemEntity(productId, 1f, null, null, LocalDate.now(), "item-$it"))
        }
        val removed = db.itemDao().getItemsByProduct(productId).take(10)
        val deletion = InventoryOperation("delete-10", removed.map {
            InventoryChange(InventoryEntityType.ITEM, it.serverId!!, 1, deleted = true)
        })
        outbox.enqueue("account-a", deletion, 123L) {
            removed.forEach { db.itemDao().deleteItem(it.uid) }
        }

        assertEquals(5, db.itemDao().getItemsByProduct(productId).size)
        val entry = db.syncOutboxDao().head("account-a")!!
        assertEquals(deletion, InventoryOperation.decode(entry.payload, entry.payloadVersion))
    }

    @Test
    fun deliveryMetadata_doesNotChangeOperationIdentityOrPayload() = runTest {
        outbox.enqueue("account-a", operation(), 123L) {}
        val original = db.syncOutboxDao().head("account-a")!!
        db.syncOutboxDao().recordAttempt("account-a", "op-1")
        db.syncOutboxDao().recordTransientFailure("account-a", "op-1", "UNAVAILABLE")
        val retry = db.syncOutboxDao().head("account-a")!!

        assertEquals(original.payload, retry.payload)
        assertEquals(original.operationId, retry.operationId)
        assertEquals(1, retry.attempts)
        assertEquals("UNAVAILABLE", retry.lastErrorCode)
    }

    @Test
    fun accountIsolation_doesNotReadAcknowledgeOrModifyAnotherAccountsOperations() = runTest {
        outbox.enqueue("account-a", operation(), 123L) {}
        assertNull(db.syncOutboxDao().head("account-b"))
        assertEquals(0, db.syncOutboxDao().acknowledge("account-b", "op-1"))
        assertEquals(0, db.syncOutboxDao().recordAttempt("account-b", "op-1"))
        assertNotNull(db.syncOutboxDao().head("account-a"))
    }

    @Test
    fun blockedHead_remainsAheadOfDependentOperations_regardlessOfTimestamps() = runTest {
        outbox.enqueue("account-a", operation("first"), 999L) {}
        outbox.enqueue("account-a", operation("second"), 1L) {}
        db.syncOutboxDao().block("account-a", "first", "REVISION_CONFLICT")

        assertEquals("first", db.syncOutboxDao().head("account-a")!!.operationId)
        assertEquals(SyncOutboxEntry.BLOCKED, db.syncOutboxDao().head("account-a")!!.status)
        assertEquals(0, db.syncOutboxDao().recordAttempt("account-a", "first"))
    }

    @Test
    fun payload_unknownVersionOrDuplicateEntities_isRejected() {
        assertTrue(runCatching { InventoryOperation.decode(operation().encode(), 99) }.isFailure)
        assertTrue(runCatching { operation().copy(changes = operation().changes + operation().changes).encode() }.isFailure)
    }

    @Test
    fun acknowledge_appliesEffectsAndRemovesTheIntentInOneCommit() = runTest {
        val productUid = db.productDao().insertProduct(ProductEntity("Milk", entityId = "p1")).toInt()
        outbox.enqueue("account-a", operation(), 123L) {}

        outbox.acknowledge("account-a", "op-1", listOf(
            InventoryOperationEffect(InventoryEntityKey(InventoryEntityType.PRODUCT, "p1"), 3, deleted = false, written = true),
        ))

        assertEquals(3L, db.productDao().findProductById(productUid)!!.revision)
        assertNull(db.syncOutboxDao().head("account-a"))
    }

    @Test
    fun acknowledge_unknownIntent_rollsBackItsEffects() = runTest {
        val productUid = db.productDao().insertProduct(ProductEntity("Milk", entityId = "p1")).toInt()

        val result = runCatching {
            outbox.acknowledge("account-a", "missing", listOf(
                InventoryOperationEffect(InventoryEntityKey(InventoryEntityType.PRODUCT, "p1"), 3, deleted = false, written = true),
            ))
        }

        assertTrue(result.isFailure)
        assertEquals(0L, db.productDao().findProductById(productUid)!!.revision)
    }
}
