package dev.pcha.foodsense.app.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseFirestoreSyncRepositoryTest {

    private lateinit var repository: FirebaseFirestoreSyncRepository
    private lateinit var userId: String
    private lateinit var session: FirebaseEmulatorSession
    private val firestore: FirebaseFirestore get() = session.firestore

    @Before
    fun setUp() = firebaseEmulatorTest {
        session = FirebaseEmulatorSession.open()
        userId = session.userId
        repository = FirebaseFirestoreSyncRepository(firestore)
    }

    @After
    fun tearDown() = firebaseEmulatorTest {
        if (::session.isInitialized) session.close()
    }

    @Test
    fun upsertProduct_newProduct_assignsIdsAndRoundTripsThroughFetchAll() = firebaseEmulatorTest {
        val stored = repository.upsertProduct(userId, product(name = "Milk", items = listOf(item(quantity = 2f))))

        assertTrue(stored.serverId.isNotBlank())
        assertTrue(stored.items.single().id.isNotBlank())

        val fetched = repository.fetchAll(userId).single()
        assertEquals(stored.serverId, fetched.serverId)
        assertEquals("Milk", fetched.name)
        assertEquals(2f, fetched.items.single().quantity)
        assertEquals(stored.items.single().id, fetched.items.single().id)
    }

    @Test
    fun upsertProduct_existingServerId_updatesInPlace() = firebaseEmulatorTest {
        val stored = repository.upsertProduct(userId, product(name = "Milk"))

        repository.upsertProduct(userId, stored.copy(name = "Oat Milk"))

        val fetched = repository.fetchAll(userId).single()
        assertEquals(stored.serverId, fetched.serverId)
        assertEquals("Oat Milk", fetched.name)
    }

    @Test
    fun updateProductItems_replacesItemsWithoutClobberingName() = firebaseEmulatorTest {
        val stored = repository.upsertProduct(userId, product(name = "Milk", items = listOf(item(quantity = 1f))))

        repository.updateProductItems(userId, stored.serverId, listOf(item(id = "i-new", quantity = 5f)), 999L)

        val fetched = repository.fetchAll(userId).single()
        assertEquals("Milk", fetched.name)
        assertEquals(listOf(5f), fetched.items.map { it.quantity })
        assertEquals(999L, fetched.updatedAt)
    }

    @Test
    fun updateProductItems_blankIds_returnsResolvedIdsMatchingWhatWasStored() = firebaseEmulatorTest {
        val stored = repository.upsertProduct(userId, product(name = "Milk"))

        val resolved = repository.updateProductItems(
            userId, stored.serverId, listOf(item(quantity = 1f), item(quantity = 2f)), 5L
        )

        assertTrue(resolved.all { it.id.isNotBlank() })
        assertNotEquals(resolved[0].id, resolved[1].id)
        assertEquals(resolved.map { it.id }, repository.fetchAll(userId).single().items.map { it.id })
    }

    @Test
    fun deleteProduct_removesItFromFetchAll() = firebaseEmulatorTest {
        val stored = repository.upsertProduct(userId, product(name = "Milk"))

        repository.deleteProduct(userId, stored.serverId)

        assertEquals(emptyList<FirestoreProduct>(), repository.fetchAll(userId))
    }

    @Test
    fun listenToChanges_remoteWrite_isEmitted() = firebaseEmulatorTest {
        repository.upsertProduct(userId, product(name = "Milk"))

        val emitted = withTimeout(TIMEOUT_MS) {
            repository.listenToChanges(userId).first { it.isNotEmpty() }
        }

        assertEquals("Milk", emitted.single().name)
    }

    @Test
    fun fetchAll_documentMissingOptionalFields_fallsBackToDefaults() = firebaseEmulatorTest {
        firestore.collection("users").document(userId).collection("products").document("legacy")
            .set(mapOf("name" to "Milk", "items" to listOf(mapOf("id" to "i1"))))
            .await()

        val fetched = repository.fetchAll(userId).single()

        assertEquals(1f, fetched.items.single().quantity)
        assertEquals(0L, fetched.items.single().addedAt)
        assertNull(fetched.items.single().unit)
        assertEquals(0L, fetched.updatedAt)
    }

    @Test
    fun fetchAll_documentWithoutName_isDiscarded() = firebaseEmulatorTest {
        firestore.collection("users").document(userId).collection("products").document("nameless")
            .set(mapOf("items" to emptyList<Map<String, Any?>>()))
            .await()

        assertEquals(emptyList<FirestoreProduct>(), repository.fetchAll(userId))
    }

    @Test
    fun upsertProduct_blankItemIds_areAssignedDistinctIds() = firebaseEmulatorTest {
        val stored = repository.upsertProduct(
            userId,
            product(name = "Milk", items = listOf(item(quantity = 1f), item(quantity = 2f))),
        )

        val ids = stored.items.map { it.id }
        assertTrue(ids.all { it.isNotBlank() })
        assertNotEquals(ids[0], ids[1])
    }

    private fun product(name: String, serverId: String = "", items: List<FirestoreItem> = emptyList()) =
        FirestoreProduct(serverId = serverId, name = name, items = items, updatedAt = 1L)

    private fun item(id: String = "", quantity: Float = 1f) =
        FirestoreItem(id = id, quantity = quantity, unit = "L", expirationDate = 20_000L, addedAt = 19_000L)

    companion object {
        private const val TIMEOUT_MS = 10_000L
    }
}
