package dev.pcha.foodsense.app.data.sync.outbox

import dev.pcha.foodsense.app.data.local.database.AppDatabase
import dev.pcha.foodsense.app.data.local.database.SyncOutboxEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drains one account's outbox through an [InventoryOperationRemote], oldest intent first.
 *
 * Not bound in Hilt and not scheduled yet: the durable worker that calls it, plus local revision
 * reconciliation, must exist before this is activated. See `CLAUDE.md`.
 *
 * Ordering is the contract: intents are applied strictly FIFO, and the first one that cannot be
 * applied stops the drain. A later intent may depend on an earlier one (an item add after its product
 * create), so letting it overtake would apply changes against a state the server does not have yet.
 *
 * Failure policy, in terms of the protocol taxonomy:
 * - [InventoryTransientFailure]: the intent stays PENDING with the error code recorded; the drain stops
 *   and a later run retries it. Retrying is safe because the remote deduplicates by operation ID.
 * - [InventoryPermanentFailure], [InventoryRevisionConflict] and [InventoryOperationIdReused]: the intent
 *   is BLOCKED, never dropped, and the drain stops behind it until resolution is implemented.
 * - Coroutine cancellation propagates and leaves the row untouched.
 *
 * A crash between a successful [InventoryOperationRemote.apply] and the acknowledgement is safe: the
 * remote receipt makes the replayed operation a no-op, and the intent is simply sent again.
 */
class InventoryOutboxDrain(
    private val database: AppDatabase,
    private val remote: InventoryOperationRemote,
) {
    private val mutex = Mutex()

    suspend fun drain(accountId: String): DrainReport {
        require(accountId.isNotBlank())
        return mutex.withLock { drainFifo(accountId) }
    }

    private suspend fun drainFifo(accountId: String): DrainReport {
        val dao = database.syncOutboxDao()
        val acknowledged = mutableListOf<String>()
        while (true) {
            // Blocked entries are read too: a conflict must not let dependent intents overtake it.
            val entry = dao.head(accountId) ?: return DrainReport(acknowledged)
            if (entry.status == SyncOutboxEntry.BLOCKED) {
                return DrainReport(acknowledged, blocked = BlockedOperation(entry.operationId, entry.lastErrorCode))
            }

            val operation = runCatching { InventoryOperation.decode(entry.payload, entry.payloadVersion) }
                .getOrElse {
                    dao.block(accountId, entry.operationId, CODE_INVALID_PAYLOAD)
                    return DrainReport(acknowledged, blocked = BlockedOperation(entry.operationId, CODE_INVALID_PAYLOAD))
                }

            // Count the attempt before the network call so a crash mid-flight is still charged.
            dao.recordAttempt(accountId, entry.operationId)
            try {
                remote.apply(accountId, operation)
            } catch (error: InventoryTransientFailure) {
                dao.recordTransientFailure(accountId, entry.operationId, error.code)
                return DrainReport(acknowledged, deferred = DeferredOperation(entry.operationId, error.code))
            } catch (error: InventoryPermanentFailure) {
                dao.block(accountId, entry.operationId, error.code)
                return DrainReport(acknowledged, blocked = BlockedOperation(entry.operationId, error.code))
            } catch (error: InventoryRevisionConflict) {
                dao.block(accountId, entry.operationId, CODE_REVISION_CONFLICT)
                return DrainReport(acknowledged, blocked = BlockedOperation(entry.operationId, CODE_REVISION_CONFLICT))
            } catch (error: InventoryOperationIdReused) {
                dao.block(accountId, entry.operationId, CODE_OPERATION_ID_REUSED)
                return DrainReport(acknowledged, blocked = BlockedOperation(entry.operationId, CODE_OPERATION_ID_REUSED))
            }

            check(dao.acknowledge(accountId, entry.operationId) == 1) {
                "Outbox entry ${entry.operationId} disappeared while draining"
            }
            acknowledged += entry.operationId
        }
    }

    companion object {
        const val CODE_REVISION_CONFLICT = "REVISION_CONFLICT"
        const val CODE_OPERATION_ID_REUSED = "OPERATION_ID_REUSED"
        const val CODE_INVALID_PAYLOAD = "INVALID_PAYLOAD"
    }
}

data class DrainReport(
    val acknowledged: List<String> = emptyList(),
    val blocked: BlockedOperation? = null,
    val deferred: DeferredOperation? = null,
)

data class BlockedOperation(val operationId: String, val code: String?)

data class DeferredOperation(val operationId: String, val code: String)
