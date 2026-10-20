package dev.pcha.foodsense.app.data.sync.outbox

import android.util.Log
import dev.pcha.foodsense.app.BuildConfig
import javax.inject.Inject

/**
 * V2 write path for local mutations: turns the rows a mutation just wrote into versioned intents and
 * commits them with the optimistic revision bump, inside the caller's transaction.
 *
 * The app binds [DisabledInventoryIntents] while the protocol stays dark: nothing drains the outbox yet,
 * so a device must not accumulate intents it cannot deliver, and the legacy sender is still the one
 * keeping accounts in sync. The call sites are the real mutation sites, so activating the V2 writer is
 * that one binding plus the durable worker.
 */
interface InventoryIntentRecorder {
    /**
     * @param operations builds the intents from the rows the caller has already written in this
     *   transaction; empty when the action has no representable change.
     */
    suspend fun record(accountId: String?, createdAt: Long, operations: () -> List<InventoryOperation>)

    /**
     * Removes pending intents that can no longer be delivered, in the caller's transaction.
     *
     * Called when the device stops belonging to the account they were written for (sign-out or a
     * different account signing in). See [InventoryOutbox.discardPending] for why they are dropped and
     * why the revisions they reserved are rewound.
     */
    suspend fun discardPending()
}

/** Keeps the V2 writer dark. Never builds a payload. */
class DisabledInventoryIntents @Inject constructor() : InventoryIntentRecorder {
    override suspend fun record(accountId: String?, createdAt: Long, operations: () -> List<InventoryOperation>) = Unit

    override suspend fun discardPending() = Unit
}

class OutboxInventoryIntents @Inject constructor(private val outbox: InventoryOutbox) : InventoryIntentRecorder {
    override suspend fun record(accountId: String?, createdAt: Long, operations: () -> List<InventoryOperation>) {
        // Signed out: these rows are local-only and upload on the next sign-in, like the legacy path.
        if (accountId == null) return
        val intents = operations()
        if (intents.isEmpty()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Local action has no representable V2 change; local commit kept")
            return
        }
        outbox.recordCommitted(accountId, intents, createdAt)
    }

    override suspend fun discardPending() = outbox.discardPending()

    private companion object {
        const val TAG = "FoodSenseSync"
    }
}
