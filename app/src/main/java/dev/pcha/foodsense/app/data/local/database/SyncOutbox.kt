package dev.pcha.foodsense.app.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Payload and ownership never change after insertion; retries only update delivery metadata. */
@Entity(tableName = "sync_outbox", indices = [Index(value = ["operationId"], unique = true), Index("accountId")])
data class SyncOutboxEntry(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val operationId: String,
    val accountId: String,
    val payloadVersion: Int,
    val payload: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val status: String = PENDING,
    val lastErrorCode: String? = null,
) {
    companion object {
        const val PENDING = "PENDING"
        const val BLOCKED = "BLOCKED"
    }
}

@Dao
interface SyncOutboxDao {
    @Insert
    suspend fun insert(entry: SyncOutboxEntry): Long

    // Include blocked entries: a conflict must not silently let dependent operations overtake it.
    @Query("SELECT * FROM sync_outbox WHERE accountId = :accountId ORDER BY sequence LIMIT 1")
    suspend fun head(accountId: String): SyncOutboxEntry?

    @Query("SELECT * FROM sync_outbox WHERE accountId = :accountId ORDER BY sequence")
    fun observe(accountId: String): Flow<List<SyncOutboxEntry>>

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastErrorCode = NULL WHERE accountId = :accountId AND operationId = :operationId AND status = 'PENDING'")
    suspend fun recordAttempt(accountId: String, operationId: String): Int

    @Query("UPDATE sync_outbox SET lastErrorCode = :code WHERE accountId = :accountId AND operationId = :operationId AND status = 'PENDING'")
    suspend fun recordTransientFailure(accountId: String, operationId: String, code: String): Int

    @Query("UPDATE sync_outbox SET status = 'BLOCKED', lastErrorCode = :code WHERE accountId = :accountId AND operationId = :operationId")
    suspend fun block(accountId: String, operationId: String, code: String): Int

    // Only call inside the transaction applying a verified server acknowledgement.
    @Query("DELETE FROM sync_outbox WHERE accountId = :accountId AND operationId = :operationId")
    suspend fun acknowledge(accountId: String, operationId: String): Int
}
