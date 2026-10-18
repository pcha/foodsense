package dev.pcha.foodsense.app.data.sync.outbox

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pcha.foodsense.app.data.local.database.AppDatabase
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
        drain = InventoryOutboxDrain(db, remote)
    }

    @After
    fun tearDown() = db.close()

    private fun operation(id: String) = InventoryOperation(id, listOf(
        InventoryChange(InventoryEntityType.ITEM, "item-$id", 0, mapOf("quantity" to JsonPrimitive(1))),
    ))

    private suspend fun enqueue(accountId: String, vararg ids: String) {
        ids.forEach { outbox.enqueue(accountId, operation(it), 123L) {} }
    }

    private suspend fun head(accountId: String) = db.syncOutboxDao().head(accountId)

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

    private fun recordingReceipt(operation: InventoryOperation) =
        InventoryOperationReceipt(operation.id, "fingerprint", emptyList())

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
