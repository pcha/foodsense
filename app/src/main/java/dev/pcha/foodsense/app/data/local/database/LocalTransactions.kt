package dev.pcha.foodsense.app.data.local.database

import androidx.room.withTransaction
import javax.inject.Inject

/** Groups a complete user action into one local commit, independently of network availability. */
interface LocalTransactions {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomLocalTransactions @Inject constructor(private val database: AppDatabase) : LocalTransactions {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction(block)
}
