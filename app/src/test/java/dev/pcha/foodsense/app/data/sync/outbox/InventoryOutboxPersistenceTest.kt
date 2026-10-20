package dev.pcha.foodsense.app.data.sync.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pcha.foodsense.app.data.local.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class InventoryOutboxPersistenceTest {
    @Test
    fun pendingOperation_databaseReopened_preservesPayloadOrderAndRetryState() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "outbox-${UUID.randomUUID()}"
        val operation = InventoryOperation("op-1", listOf(
            InventoryChange(InventoryEntityType.ITEM, "item-1", 3, deleted = true),
        ))
        try {
            val first = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                InventoryOutbox(first).enqueue("account-a", listOf(operation), 123L) {}
                first.syncOutboxDao().recordAttempt("account-a", operation.id)
                first.syncOutboxDao().recordTransientFailure("account-a", operation.id, "UNAVAILABLE")
            } finally {
                first.close()
            }
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                val entry = reopened.syncOutboxDao().head("account-a")!!
                assertEquals(operation, InventoryOperation.decode(entry.payload, entry.payloadVersion))
                assertEquals(1, entry.attempts)
                assertEquals("UNAVAILABLE", entry.lastErrorCode)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
