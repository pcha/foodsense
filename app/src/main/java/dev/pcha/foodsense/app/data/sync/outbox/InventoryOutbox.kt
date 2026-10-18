package dev.pcha.foodsense.app.data.sync.outbox

import androidx.room.withTransaction
import dev.pcha.foodsense.app.data.local.database.AppDatabase
import dev.pcha.foodsense.app.data.local.database.SyncOutboxEntry
import javax.inject.Inject

/**
 * Durable store for versioned sync intents. Not connected to the legacy sender: replaying versioned
 * intents through its full-product upserts would not provide idempotency or CAS.
 *
 * It owns every `sync_outbox` transition. Three of them change local inventory state too and must be
 * atomic with it:
 *
 * - [enqueue] commits a local mutation together with the intent that describes it, and with the
 *   revisions that intent reserves.
 * - [recordCommitted] does the same for a mutation the caller already applied in its own transaction.
 * - [acknowledge] commits a verified server receipt's effects together with the intent removal.
 */
class InventoryOutbox @Inject constructor(private val database: AppDatabase) {
    suspend fun <T> enqueue(
        accountId: String,
        operation: InventoryOperation,
        createdAt: Long,
        localMutation: suspend () -> T,
    ): T {
        require(accountId.isNotBlank())
        return database.withTransaction {
            // Duplicate operation IDs fail before executing the local mutation a second time.
            insertIntent(accountId, operation, createdAt)
            localMutation().also { advanceOptimistically(operation) }
        }
    }

    /**
     * Commits the intent for a mutation the caller already applied inside this transaction, and reserves
     * the revisions that intent will produce.
     *
     * Call it **after** the local mutation: Room's `@Update` writes the row's own revision column, so a
     * bump made earlier in the same transaction is overwritten by the value it replaced.
     */
    suspend fun recordCommitted(accountId: String, operation: InventoryOperation, createdAt: Long) {
        require(accountId.isNotBlank())
        database.withTransaction {
            insertIntent(accountId, operation, createdAt)
            advanceOptimistically(operation)
        }
    }

    private suspend fun insertIntent(accountId: String, operation: InventoryOperation, createdAt: Long) {
        database.syncOutboxDao().insert(SyncOutboxEntry(
            operationId = operation.id,
            accountId = accountId,
            payloadVersion = InventoryOperation.PAYLOAD_VERSION,
            payload = operation.encode(),
            createdAt = createdAt,
        ))
    }

    /**
     * Reserves the revisions an intent will produce. A stored revision then means "the revision the
     * server knows, plus every write this device has queued for that entity", so a second intent for the
     * same entity uses a base the server will still accept instead of one its own queue already
     * overtook. The server assigns exactly `baseRevision + 1` to every entity an operation writes, and
     * FIFO order is what keeps that arithmetic valid.
     *
     * Monotonic, like a receipt's advancement. A deletion has nothing to reserve: it leaves no local row,
     * and the server only tombstones an entity the intent deletes without moving its revision on.
     */
    private suspend fun advanceOptimistically(operation: InventoryOperation) {
        operation.changes.forEach { change ->
            if (change.deleted) return@forEach
            val revision = change.baseRevision + 1
            when (change.type) {
                InventoryEntityType.PRODUCT -> database.productDao()
                    .findProductByEntityId(change.entityId)
                    ?.let { database.productDao().advanceRevision(it.uid, revision) }
                InventoryEntityType.ITEM -> database.itemDao()
                    .findItemByEntityId(change.entityId)
                    ?.let { database.itemDao().advanceRevision(it.uid, revision) }
            }
        }
    }

    /** Oldest intent for the account, blocked entries included, so nothing overtakes a conflict. */
    suspend fun head(accountId: String): SyncOutboxEntry? = database.syncOutboxDao().head(accountId)

    suspend fun recordAttempt(accountId: String, operationId: String) {
        database.syncOutboxDao().recordAttempt(accountId, operationId)
    }

    suspend fun recordTransientFailure(accountId: String, operationId: String, code: String) {
        database.syncOutboxDao().recordTransientFailure(accountId, operationId, code)
    }

    suspend fun block(accountId: String, operationId: String, code: String) {
        database.syncOutboxDao().block(accountId, operationId, code)
    }

    /**
     * Applies a verified server acknowledgement. Every effect the server actually wrote advances its
     * local entity to the revision the server assigned, and then the intent is removed - in one
     * transaction, so a crash can only leave the intent in place. Replaying it returns the stored
     * receipt and re-applies the same effects as a no-op, because a revision never moves backwards.
     *
     * Effects are skipped when they carry nothing new: the user can delete a row while its intent is
     * still queued (the deletion has its own intent), and remote deletions are reconciliation's job,
     * not the receipt of a local one.
     */
    suspend fun acknowledge(accountId: String, operationId: String, effects: List<InventoryOperationEffect>) {
        database.withTransaction {
            effects.forEach { effect ->
                if (effect.deleted || !effect.written) return@forEach
                when (effect.key.type) {
                    InventoryEntityType.PRODUCT -> database.productDao()
                        .findProductByEntityId(effect.key.id)
                        ?.let { database.productDao().advanceRevision(it.uid, effect.revision) }
                    InventoryEntityType.ITEM -> database.itemDao()
                        .findItemByEntityId(effect.key.id)
                        ?.let { database.itemDao().advanceRevision(it.uid, effect.revision) }
                }
            }
            check(database.syncOutboxDao().acknowledge(accountId, operationId) == 1) {
                "Outbox entry $operationId disappeared while applying its receipt"
            }
        }
    }
}
