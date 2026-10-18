package dev.pcha.foodsense.app.data.sync.outbox

import androidx.room.withTransaction
import dev.pcha.foodsense.app.data.local.database.AppDatabase
import dev.pcha.foodsense.app.data.local.database.SyncOutboxEntry
import javax.inject.Inject

/**
 * Building block for the versioned sync protocol. Not connected to the legacy sender: replaying
 * versioned intents through its full-product upserts would not provide idempotency or CAS.
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
}
