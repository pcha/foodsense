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
class InventoryOutboxDrainTest {
    private lateinit var db: AppDatabase
    private lateinit var outbox: InventoryOutbox
    private lateinit var remote: RecordingRemote
    private lateinit var drain: InventoryOutboxDrain

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        outbox = InventoryOutbox(db)
        remote = RecordingRemote()
        drain = InventoryOutboxDrain(outbox, remote)
    }

    @After
    fun tearDown() = db.close()

    private fun operation(id: String) = InventoryOperation(id, listOf(
        InventoryChange(InventoryEntityType.ITEM, "item-$id", 0, mapOf("quantity" to JsonPrimitive(1))),
    ))

    private suspend fun enqueue(accountId: String, vararg ids: String) {
        ids.forEach { outbox.enqueue(accountId, listOf(operation(it)), 123L) {} }
    }

    private suspend fun head(accountId: String) = db.syncOutboxDao().head(accountId)

    private suspend fun insertProduct(name: String = "Milk", entityId: String = "p1"): Int =
        db.productDao().insertProduct(ProductEntity(name = name, entityId = entityId)).toInt()

    private suspend fun insertItem(productId: Int, entityId: String, quantity: Float = 1f) =
        db.itemDao().insertItem(ItemEntity(productId, quantity, null, null, LocalDate.now(), entityId = entityId))

    @Test
    fun drain_withoutPendingOperations_sendsNothing() = runTest {
        val report = drain.drain("account-a")

        assertEquals(DrainReport(), report)
        assertTrue(remote.applied.isEmpty())
    }

    @Test
    fun drain_pendingIntents_appliedInFifoOrderAndAcknowledged() = runTest {
        enqueue("account-a", "first", "second", "third")

        val report = drain.drain("account-a")

        assertEquals(listOf("first", "second", "third"), remote.applied)
        assertEquals(listOf("first", "second", "third"), report.acknowledged)
        assertNull(report.blocked)
        assertNull(report.deferred)
        assertNull(head("account-a"))
    }

    @Test
    fun drain_transientFailure_keepsIntentPendingAndStopsBehindIt() = runTest {
        enqueue("account-a", "first", "second")
        remote.behavior = { throw InventoryTransientFailure("UNAVAILABLE") }

        val report = drain.drain("account-a")

        assertEquals(listOf("first"), remote.applied)
        assertEquals(DeferredOperation("first", "UNAVAILABLE"), report.deferred)
        assertTrue(report.acknowledged.isEmpty())
        val entry = head("account-a")!!
        assertEquals("first", entry.operationId)
        assertEquals(SyncOutboxEntry.PENDING, entry.status)
        assertEquals("UNAVAILABLE", entry.lastErrorCode)
        assertEquals(1, entry.attempts)
        assertEquals(listOf("first", "second"), db.syncOutboxDao().observe("account-a").first().map { it.operationId })
    }

    @Test
    fun drain_transientFailureThenSuccess_retriesAndClearsTheError() = runTest {
        enqueue("account-a", "first")
        remote.behavior = { throw InventoryTransientFailure("UNAVAILABLE") }
        drain.drain("account-a")

        remote.behavior = { recordingReceipt(it) }
        val report = drain.drain("account-a")

        assertEquals(listOf("first"), report.acknowledged)
        assertNull(head("account-a"))
    }

    @Test
    fun drain_permanentFailure_blocksIntentInsteadOfDroppingIt() = runTest {
        enqueue("account-a", "first", "second")
        remote.behavior = { throw InventoryPermanentFailure("PERMISSION_DENIED") }

        val report = drain.drain("account-a")

        assertEquals(listOf("first"), remote.applied)
        assertEquals(BlockedOperation("first", "PERMISSION_DENIED"), report.blocked)
        val entry = head("account-a")!!
        assertEquals(SyncOutboxEntry.BLOCKED, entry.status)
        assertEquals("PERMISSION_DENIED", entry.lastErrorCode)
        assertEquals(2, db.syncOutboxDao().observe("account-a").first().size)
    }

    @Test
    fun drain_revisionConflict_blocksWithConflictCodeWithoutRetrying() = runTest {
        enqueue("account-a", "first")
        remote.behavior = {
            throw InventoryRevisionConflict(
                InventoryEntityKey(InventoryEntityType.ITEM, "item-first"), 0, 3, InventoryConflictReason.REVISION_MISMATCH,
            )
        }

        val report = drain.drain("account-a")

        assertEquals(BlockedOperation("first", InventoryOutboxDrain.CODE_REVISION_CONFLICT), report.blocked)
        assertEquals(SyncOutboxEntry.BLOCKED, head("account-a")!!.status)

        remote.behavior = { recordingReceipt(it) }
        val second = drain.drain("account-a")
        assertEquals(listOf("first"), remote.applied)
        assertEquals(BlockedOperation("first", InventoryOutboxDrain.CODE_REVISION_CONFLICT), second.blocked)
    }

    @Test
    fun drain_reusedOperationId_blocksInsteadOfRetrying() = runTest {
        enqueue("account-a", "first")
        remote.behavior = { throw InventoryOperationIdReused(it.id) }

        val report = drain.drain("account-a")

        assertEquals(BlockedOperation("first", InventoryOutboxDrain.CODE_OPERATION_ID_REUSED), report.blocked)
        assertEquals(SyncOutboxEntry.BLOCKED, head("account-a")!!.status)
    }

    @Test
    fun drain_corruptPayload_blocksWithoutCallingTheRemote() = runTest {
        db.syncOutboxDao().insert(SyncOutboxEntry(
            operationId = "corrupt", accountId = "account-a", payloadVersion = 1, payload = "{not json", createdAt = 1L,
        ))

        val report = drain.drain("account-a")

        assertTrue(remote.applied.isEmpty())
        assertEquals(BlockedOperation("corrupt", InventoryOutboxDrain.CODE_INVALID_PAYLOAD), report.blocked)
        assertEquals(SyncOutboxEntry.BLOCKED, head("account-a")!!.status)
    }

    @Test
    fun drain_blockedHead_isNotOvertakenByLaterIntents() = runTest {
        enqueue("account-a", "first", "second")
        db.syncOutboxDao().block("account-a", "first", "REVISION_CONFLICT")

        val report = drain.drain("account-a")

        assertTrue(remote.applied.isEmpty())
        assertEquals("first", report.blocked!!.operationId)
        assertTrue(db.syncOutboxDao().observe("account-a").first().any { it.operationId == "second" })
    }

    @Test
    fun drain_afterBlockedHeadIsResolved_continuesWithRemainingIntents() = runTest {
        enqueue("account-a", "first", "second")
        db.syncOutboxDao().block("account-a", "first", "REVISION_CONFLICT")
        drain.drain("account-a")
        assertTrue(remote.applied.isEmpty())

        db.syncOutboxDao().acknowledge("account-a", "first")
        remote.behavior = { recordingReceipt(it) }
        val report = drain.drain("account-a")

        assertEquals(listOf("second"), report.acknowledged)
        assertNull(head("account-a"))
    }

    @Test
    fun drain_cancellation_propagatesAndLeavesTheIntentPending() = runTest {
        enqueue("account-a", "first")
        remote.behavior = { throw CancellationException("cancelled") }

        try {
            drain.drain("account-a")
            fail("Expected cancellation to propagate")
        } catch (_: CancellationException) {
            val entry = head("account-a")!!
            assertEquals(SyncOutboxEntry.PENDING, entry.status)
            assertEquals(1, entry.attempts)
        }
    }

    @Test
    fun drain_onlyTouchesTheGivenAccount() = runTest {
        enqueue("account-a", "a-1")
        enqueue("account-b", "b-1")

        val report = drain.drain("account-a")

        assertEquals(listOf("a-1"), report.acknowledged)
        assertEquals("b-1", head("account-b")!!.operationId)
        assertEquals(1, db.syncOutboxDao().observe("account-b").first().size)
    }

    @Test
    fun drain_acknowledgedEffects_advanceLocalRevisions() = runTest {
        val productUid = insertProduct()
        insertItem(productUid, "i1")
        remote.behavior = ::appliedReceipt
        val product = db.productDao().findProductById(productUid)!!
        val item = db.itemDao().getItemsByProduct(productUid).single()
        outbox.enqueue("account-a", listOf(InventoryOperation("create", listOf(
            LocalInventoryChanges.product(product),
            LocalInventoryChanges.item(item, product.entityId),
        ))), 1L) {}

        val report = drain.drain("account-a")

        assertEquals(listOf("create"), report.acknowledged)
        assertEquals(1L, db.productDao().findProductById(productUid)!!.revision)
        assertEquals(1L, db.itemDao().getItemsByProduct(productUid).single().revision)
        assertNull(head("account-a"))
    }

    @Test
    fun drain_secondEditCarriesTheRevisionFromTheFirstReceipt() = runTest {
        val productUid = insertProduct()
        val created = db.productDao().findProductById(productUid)!!
        remote.behavior = ::appliedReceipt
        outbox.enqueue("account-a", listOf(InventoryOperation("create", listOf(LocalInventoryChanges.product(created)))), 1L) {}
        drain.drain("account-a")

        val renamed = db.productDao().findProductById(productUid)!!.copy(name = "Pears")
        db.productDao().updateProduct(renamed)
        val change = LocalInventoryChanges.product(renamed)

        // The receipt's revision is what makes this edit a valid compare-and-set instead of a conflict.
        assertEquals(1L, change.baseRevision)
        val recorded = mutableListOf<InventoryOperation>()
        remote.behavior = { operation -> recorded += operation; appliedReceipt(operation) }
        outbox.enqueue("account-a", listOf(InventoryOperation("rename", listOf(change))), 2L) {}

        drain.drain("account-a")

        assertEquals(1L, recorded.single().changes.single().baseRevision)
        assertEquals(2L, db.productDao().findProductById(productUid)!!.revision)
    }

    @Test
    fun drain_olderAcknowledgedRevision_neverMovesRevisionBackwards() = runTest {
        val productUid = insertProduct()
        db.productDao().advanceRevision(productUid, 5L)
        remote.behavior = {
            InventoryOperationReceipt(it.id, "fingerprint", listOf(
                InventoryOperationEffect(InventoryEntityKey(InventoryEntityType.PRODUCT, "p1"), 2, deleted = false, written = true),
            ))
        }

        enqueue("account-a", "first")
        drain.drain("account-a")

        assertEquals(5L, db.productDao().findProductById(productUid)!!.revision)
        assertNull(head("account-a"))
    }

    @Test
    fun drain_effectForAnEntityThatIsGoneLocally_isStillAcknowledged() = runTest {
        remote.behavior = {
            InventoryOperationReceipt(it.id, "fingerprint", listOf(
                InventoryOperationEffect(InventoryEntityKey(InventoryEntityType.PRODUCT, "p1"), 1, deleted = false, written = true),
                InventoryOperationEffect(InventoryEntityKey(InventoryEntityType.ITEM, "i1"), 1, deleted = false, written = true),
            ))
        }

        enqueue("account-a", "first")
        val report = drain.drain("account-a")

        assertEquals(listOf("first"), report.acknowledged)
        assertNull(head("account-a"))
    }

    @Test
    fun drain_deletionEffect_leavesLocalRowsAlone() = runTest {
        val productUid = insertProduct()
        remote.behavior = {
            InventoryOperationReceipt(it.id, "fingerprint", listOf(
                InventoryOperationEffect(InventoryEntityKey(InventoryEntityType.PRODUCT, "p1"), 1, deleted = true, written = true),
            ))
        }

        enqueue("account-a", "first")
        drain.drain("account-a")

        // Local existence is decided by the local mutation, not by acknowledging its own intent.
        val product = db.productDao().findProductById(productUid)
        assertNotNull(product)
        assertEquals(0L, product!!.revision)
    }

    @Test
    fun drain_revisionConflict_leavesLocalRevisionsUntouched() = runTest {
        val productUid = insertProduct()
        remote.behavior = {
            throw InventoryRevisionConflict(
                InventoryEntityKey(InventoryEntityType.PRODUCT, "p1"), 0, 1, InventoryConflictReason.REVISION_MISMATCH,
            )
        }

        enqueue("account-a", "first")
        val report = drain.drain("account-a")

        assertEquals(BlockedOperation("first", InventoryOutboxDrain.CODE_REVISION_CONFLICT), report.blocked)
        assertEquals(0L, db.productDao().findProductById(productUid)!!.revision)
    }

    private fun recordingReceipt(operation: InventoryOperation) =
        InventoryOperationReceipt(operation.id, "fingerprint", emptyList())

    /** Mirrors `InventoryOperationProtocol.plan` on the happy path: every change is written at base + 1. */
    private fun appliedReceipt(operation: InventoryOperation) = InventoryOperationReceipt(
        operation.id,
        "fingerprint",
        operation.changes.map { change ->
            InventoryOperationEffect(
                InventoryEntityKey(change.type, change.entityId),
                revision = change.baseRevision + 1,
                deleted = change.deleted,
                written = true,
            )
        },
    )

    private class RecordingRemote(
        var behavior: (InventoryOperation) -> InventoryOperationReceipt = {
            InventoryOperationReceipt(it.id, "fingerprint", emptyList())
        },
    ) : InventoryOperationRemote {
        val applied = mutableListOf<String>()

        override suspend fun apply(accountId: String, operation: InventoryOperation): InventoryOperationReceipt {
            applied += operation.id
            return behavior(operation)
        }
    }
}
