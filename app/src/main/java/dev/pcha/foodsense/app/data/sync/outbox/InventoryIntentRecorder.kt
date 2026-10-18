package dev.pcha.foodsense.app.data.sync.outbox

import android.util.Log
import dev.pcha.foodsense.app.BuildConfig
import javax.inject.Inject

/**
 * V2 write path for local mutations: turns the rows a mutation just wrote into a versioned intent and
 * commits it with the optimistic revision bump, inside the caller's transaction.
 *
 * The app binds [DisabledInventoryIntents] while the protocol stays dark: nothing drains the outbox yet,
 * so a device must not accumulate intents it cannot deliver, and the legacy sender is still the one
 * keeping accounts in sync. The call sites are the real mutation sites, so activating the V2 writer is
 * that one binding plus the durable worker.
 */
interface InventoryIntentRecorder {
    /**
     * @param operation builds the intent from the rows the caller has already written in this
     *   transaction; null when the action does not fit one intent.
     */
    suspend fun record(accountId: String?, createdAt: Long, operation: () -> InventoryOperation?)
}

/** Keeps the V2 writer dark. Never builds a payload. */
class DisabledInventoryIntents @Inject constructor() : InventoryIntentRecorder {
    override suspend fun record(accountId: String?, createdAt: Long, operation: () -> InventoryOperation?) = Unit
}

class OutboxInventoryIntents @Inject constructor(private val outbox: InventoryOutbox) : InventoryIntentRecorder {
    override suspend fun record(accountId: String?, createdAt: Long, operation: () -> InventoryOperation?) {
        // Signed out: these rows are local-only and upload on the next sign-in, like the legacy path.
        if (accountId == null) return
        val intent = operation()
        if (intent == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Local action not queued as one V2 intent; local commit kept")
            return
        }
        outbox.recordCommitted(accountId, intent, createdAt)
    }

    private companion object {
        const val TAG = "FoodSenseSync"
    }
}
