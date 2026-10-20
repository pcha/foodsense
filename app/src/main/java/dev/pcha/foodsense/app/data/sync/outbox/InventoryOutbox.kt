package dev.pcha.foodsense.app.data.sync.outbox

import android.util.Log
import androidx.room.withTransaction
import dev.pcha.foodsense.app.BuildConfig
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
 * - [enqueue] commits a local mutation together with the intents that describe it, and with the
 *   revisions those intents reserve.
 * - [recordCommitted] does the same for a mutation the caller already applied in its own transaction.
 * - [acknowledge] commits a verified server receipt's effects together with the intent removal.
 *
 * One local action produces one or more intents, in order: see [LocalInventoryOperations]. They are
 * inserted together with the mutation, so the action stays atomic locally, and they travel in the
 * order they were written.
 */
class InventoryOutbox @Inject constructor(private val database: AppDatabase) {
    suspend fun <T> enqueue(
        accountId: String,
        operations: List<InventoryOperation>,
        createdAt: Long,
        localMutation: suspend () -> T,
    ): T {
        require(accountId.isNotBlank())
        require(operations.isNotEmpty())
        return database.withTransaction {
            // Duplicate operation IDs fail before executing the local mutation a second time.
            operations.forEach { insertIntent(accountId, it, createdAt) }
            localMutation().also { operations.forEach { advanceOptimistically(it) } }
        }
    }

    /**
     * Commits the intents for a mutation the caller already applied inside this transaction, and reserves
     * the revisions those intents will produce.
     *
     * Call it **after** the local mutation: Room's `@Update` writes the row's own revision column, so a
     * bump made earlier in the same transaction is overwritten by the value it replaced.
     */
    suspend fun recordCommitted(accountId: String, operations: List<InventoryOperation>, createdAt: Long) {
        require(accountId.isNotBlank())
        require(operations.isNotEmpty())
        database.withTransaction {
            operations.forEach { insertIntent(accountId, it, createdAt) }
            operations.forEach { advanceOptimistically(it) }
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
     * Drops every pending intent together with the revisions it reserved.
     *
     * This is the sign-out/account-change path: an intent belongs to the account that was signed in when
     * the mutation happened, and the legacy sender does not deliver local changes while signed out
     * either. Keeping it would be worse than losing it: the synced rows are purged with the account, and
     * a later sign-in pulls server state the stale intent no longer matches, so it would conflict
     * forever.
     *
     * Revisions are rewound instead of left inflated. A row that survives the purge (a local-only
     * product added offline) has to go back to "the revision the server knows" (0); otherwise the next
     * intent for it would base on a revision the server never reached and be rejected as a conflict.
     *
     * Runs inside the caller's transaction, together with the local purge.
     */
    suspend fun discardPending() {
        val entries = database.syncOutboxDao().getAll()
        if (entries.isEmpty()) return
        val reserved = HashMap<InventoryEntityKey, Long>()
        entries.forEach { entry ->
            InventoryOperation.decode(entry.payload, entry.payloadVersion).changes.forEach { change ->
                // A deletion reserves nothing, so it has nothing to rewind.
                if (change.deleted) return@forEach
                val key = InventoryEntityKey(change.type, change.entityId)
                // Several intents can touch one entity: the earliest base is the server's revision.
                reserved[key] = minOf(reserved[key] ?: change.baseRevision, change.baseRevision)
            }
        }
        reserved.forEach { (key, revision) ->
            when (key.type) {
                InventoryEntityType.PRODUCT -> database.productDao()
                    .findProductByEntityId(key.id)
                    ?.let { database.productDao().resetRevision(it.uid, revision) }
                InventoryEntityType.ITEM -> database.itemDao()
                    .findItemByEntityId(key.id)
                    ?.let { database.itemDao().resetRevision(it.uid, revision) }
            }
        }
        database.syncOutboxDao().deleteAll()
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
     *
     * A missing intent is not an error. Signing out discards the account's pending work while a drain
     * can still be in flight; the server write happened, its effects describe rows that are already
     * gone, and there is nothing left to remove.
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
            if (database.syncOutboxDao().acknowledge(accountId, operationId) == 0 && BuildConfig.DEBUG) {
                Log.w(TAG, "Receipt for $operationId arrived after the account's pending work was discarded")
            }
        }
    }

    private companion object {
        const val TAG = "FoodSenseSync"
    }
}
