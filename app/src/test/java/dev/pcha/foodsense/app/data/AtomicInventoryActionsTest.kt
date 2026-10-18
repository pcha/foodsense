package dev.pcha.foodsense.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pcha.foodsense.app.data.auth.AuthRepository
import dev.pcha.foodsense.app.data.auth.User
import dev.pcha.foodsense.app.data.local.database.AppDatabase
import dev.pcha.foodsense.app.data.local.database.RoomLocalTransactions
import dev.pcha.foodsense.app.data.sync.FirestoreItem
import dev.pcha.foodsense.app.data.sync.FirestoreProduct
import dev.pcha.foodsense.app.data.sync.FirestoreSyncRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Observe real Room emissions: final-state assertions with fake DAOs cannot detect partial counts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AtomicInventoryActionsTest {
    @Test
    fun batchActions_emitOnlyCommittedInventory_withoutChangingOtherProducts() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        try {
            val repository = DefaultProductRepository(
                db.productDao(), db.itemDao(), OfflineAuth(), UnusedRemote(), backgroundScope,
                RoomLocalTransactions(db),
            )
            runCurrent()
            val snapshots = Channel<Map<String, List<Float>>>(Channel.UNLIMITED)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.products.map { products ->
                    products.associate { p -> p.name to p.items.map { it.quantity } }
                }.distinctUntilChanged().collect { snapshots.send(it) }
            }
            assertEquals(emptyMap<String, List<Float>>(), snapshots.receive())

            repository.addBatch("Soap", 1f, null, null, 4)
            assertEquals(mapOf("Soap" to List(4) { 1f }), snapshots.receive())

            repository.addBatch("Apples", 1f, null, null, 15)
            assertEquals(mapOf("Soap" to List(4) { 1f }, "Apples" to List(15) { 1f }), snapshots.receive())

            val apples = repository.products.first().single { it.name == "Apples" }
            repository.deleteItems(apples.items.take(10).map { it.uid })
            assertEquals(mapOf("Soap" to List(4) { 1f }, "Apples" to List(5) { 1f }), snapshots.receive())

            repository.updateItems(apples.items.drop(10).map { it.uid }, 2f, null, null)
            assertEquals(mapOf("Soap" to List(4) { 1f }, "Apples" to List(5) { 2f }), snapshots.receive())
            job.cancel()
            snapshots.close()
        } finally {
            db.close()
        }
    }
}

private class OfflineAuth : AuthRepository {
    override val currentUser: Flow<User?> = flowOf(null)
    override suspend fun signInWithGoogle(context: Context): Result<User> = error("Unused")
    override suspend fun signInWithEmail(email: String, password: String): Result<User> = error("Unused")
    override suspend fun createAccountWithEmail(email: String, password: String): Result<User> = error("Unused")
    override suspend fun signOut() = Unit
}

private class UnusedRemote : FirestoreSyncRepository {
    override suspend fun upsertProduct(userId: String, product: FirestoreProduct): FirestoreProduct = error("Must stay offline")
    override suspend fun deleteProduct(userId: String, serverId: String): Unit = error("Must stay offline")
    override suspend fun updateProductItems(userId: String, productServerId: String, items: List<FirestoreItem>, updatedAt: Long): List<FirestoreItem> = error("Must stay offline")
    override suspend fun fetchAll(userId: String): List<FirestoreProduct> = error("Must stay offline")
    override fun listenToChanges(userId: String): Flow<List<FirestoreProduct>> = error("Must stay offline")
}
