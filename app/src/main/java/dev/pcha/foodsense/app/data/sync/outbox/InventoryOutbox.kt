package dev.pcha.foodsense.app.data.sync.outbox

import androidx.room.withTransaction
import dev.pcha.foodsense.app.data.local.database.AppDatabase
import dev.pcha.foodsense.app.data.local.database.SyncOutboxEntry
import javax.inject.Inject

/**
 * Durable store for versioned sync intents. Not connected to the legacy sender: replaying versioned
 * intents through its full-product upserts would not provide idempotency or CAS.
 *
 * It owns every `sync_outbox` transition. Two of them change local inventory state too and must be
 * atomic with it:
 *
 * - [enqueue] commits a local mutation together with the intent that describes it.
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
        val payload = operation.encode()
        return database.withTransaction {
            // Duplicate operation IDs fail before executing the local mutation a second time.
            database.syncOutboxDao().insert(SyncOutboxEntry(
                operationId = operation.id,
                accountId = accountId,
                payloadVersion = InventoryOperation.PAYLOAD_VERSION,
                payload = payload,
                createdAt = createdAt,
            ))
            localMutation()
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
