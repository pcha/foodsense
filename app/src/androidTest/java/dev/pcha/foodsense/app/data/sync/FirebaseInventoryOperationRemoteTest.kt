package dev.pcha.foodsense.app.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dev.pcha.foodsense.app.data.sync.outbox.*
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Drives the isolated V2 protocol against real auth/rules on the local emulators, never the account. */
@RunWith(AndroidJUnit4::class)
class FirebaseInventoryOperationRemoteTest {
    private val sessions = mutableListOf<FirebaseEmulatorSession>()
    private lateinit var firestore: FirebaseFirestore
    private lateinit var remote: FirebaseInventoryOperationRemote
    private lateinit var userId: String

    @Before
    fun setUp() = firebaseEmulatorTest {
        val session = FirebaseEmulatorSession.open().also { sessions += it }
        firestore = session.firestore
        userId = session.userId
        remote = FirebaseInventoryOperationRemote(firestore)
    }

    @After
    fun tearDown() = firebaseEmulatorTest {
        sessions.forEach { session -> runCatching { session.close() } }
        sessions.clear()
    }

    private fun product(id: String = "p1", revision: Long = 0, name: String = "Apples") =
        InventoryChange(InventoryEntityType.PRODUCT, id, revision, mapOf("name" to JsonPrimitive(name)))

    private fun item(id: String) = InventoryChange(InventoryEntityType.ITEM, id, 0, mapOf(
        "productId" to JsonPrimitive("p1"), "quantity" to JsonPrimitive(1), "unit" to JsonNull,
        "expirationDate" to JsonNull, "addedAt" to JsonPrimitive(20744),
    ))

    private fun entity(collection: String, id: String) =
        firestore.collection("users").document(userId).collection(collection).document(id)

    @Test
    fun apply_fifteenItemsAndProduct_commitTogetherWithReceipt() = firebaseEmulatorTest {
        val op = InventoryOperation("add15", listOf(product()) + List(15) { item("i$it") })
        val result = remote.apply(userId, op)

        assertEquals(16, result.effects.size)
        assertTrue(result.effects.all { it.revision == 1L && it.written })
        assertFalse(result.replayed)
        assertTrue(entity(FirebaseInventoryOperationRemote.OPERATIONS, op.id).get(Source.SERVER).await().exists())
        val items = firestore.collection("users").document(userId).collection(FirebaseInventoryOperationRemote.ITEMS)
            .get(Source.SERVER).await()
        assertEquals(15, items.size())
        assertTrue(items.documents.all { it.getLong("revision") == 1L })
    }

    @Test
    fun replay_afterLaterEdit_returnsOriginalReceiptWithoutOverwritingEdit() = firebaseEmulatorTest {
        val original = InventoryOperation("create", listOf(product()))
        remote.apply(userId, original)
        remote.apply(userId, InventoryOperation("rename", listOf(product(revision = 1, name = "Pears"))))

        val replay = remote.apply(userId, original)

        assertTrue(replay.replayed)
        assertEquals(1L, replay.effects.single().revision)
        val stored = entity(FirebaseInventoryOperationRemote.PRODUCTS, "p1").get(Source.SERVER).await()
        assertEquals("Pears", stored.getString("name"))
        assertEquals(2L, stored.getLong("revision"))
    }

    @Test
    fun operationId_changedPayload_isRejected() = firebaseEmulatorTest {
        remote.apply(userId, InventoryOperation("same-id", listOf(product())))
        val error = runCatching { remote.apply(userId, InventoryOperation("same-id", listOf(product(name = "Other")))) }.exceptionOrNull()
        assertTrue(error.hasCause<InventoryOperationIdReused>())
        assertEquals("Apples", entity(FirebaseInventoryOperationRemote.PRODUCTS, "p1").get(Source.SERVER).await().getString("name"))
    }

    @Test
    fun staleRevision_abortsOtherChangesAndDoesNotCreateReceipt() = firebaseEmulatorTest {
        remote.apply(userId, InventoryOperation("create", listOf(product())))
        val failed = InventoryOperation("conflict", listOf(product("p2"), product(revision = 0, name = "Stale")))

        val error = runCatching { remote.apply(userId, failed) }.exceptionOrNull()

        assertTrue(error.hasCause<InventoryRevisionConflict>())
        assertFalse(entity(FirebaseInventoryOperationRemote.PRODUCTS, "p2").get(Source.SERVER).await().exists())
        assertFalse(entity(FirebaseInventoryOperationRemote.OPERATIONS, failed.id).get(Source.SERVER).await().exists())
    }

    @Test
    fun delete_staleEditAndRepeatedDelete_preservesTombstoneAndRevision() = firebaseEmulatorTest {
        remote.apply(userId, InventoryOperation("create", listOf(product())))
        remote.apply(userId, InventoryOperation("edit", listOf(product(revision = 1, name = "Pears"))))
        val deletion = InventoryChange(InventoryEntityType.PRODUCT, "p1", 1, deleted = true)
        remote.apply(userId, InventoryOperation("delete", listOf(deletion)))
        val again = remote.apply(userId, InventoryOperation("delete-again", listOf(deletion)))

        assertFalse(again.effects.single().written)
        assertEquals(3L, again.effects.single().revision)
        val lateEdit = runCatching {
            remote.apply(userId, InventoryOperation("resurrect", listOf(product(revision = 3, name = "Late edit"))))
        }.exceptionOrNull()
        assertTrue(lateEdit.hasCause<InventoryRevisionConflict>())
        val stored = entity(FirebaseInventoryOperationRemote.PRODUCTS, "p1").get(Source.SERVER).await()
        assertTrue(stored.getBoolean("deleted")!!)
        assertEquals(3L, stored.getLong("revision"))
    }

    @Test
    fun concurrentEdits_sameBaseRevision_onlyOneSucceeds() = firebaseEmulatorTest {
        remote.apply(userId, InventoryOperation("create", listOf(product())))
        val a = async { runCatching { remote.apply(userId, InventoryOperation("a", listOf(product(revision = 1, name = "A")))) } }
        val b = async { runCatching { remote.apply(userId, InventoryOperation("b", listOf(product(revision = 1, name = "B")))) } }
        val results = listOf(a.await(), b.await())
        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull()
        assertTrue("Unexpected concurrent failure: $failure", failure.hasCause<InventoryRevisionConflict>())
        assertEquals(2L, entity(FirebaseInventoryOperationRemote.PRODUCTS, "p1").get(Source.SERVER).await().getLong("revision"))
    }

    @Test
    fun twoIndependentClients_sameAccount_detectConcurrentEditWithoutOverwriting() = firebaseEmulatorTest {
        val suffix = UUID.randomUUID().toString()
        val email = "inventory-$suffix@example.test"
        val password = "emulator-only-password"
        val first = FirebaseEmulatorSession.open {
            it.createUserWithEmailAndPassword(email, password).await()
            Unit
        }.also { sessions += it }
        val second = FirebaseEmulatorSession.open {
            it.signInWithEmailAndPassword(email, password).await()
            Unit
        }.also { sessions += it }

        firestore = first.firestore
        userId = first.userId
        remote = FirebaseInventoryOperationRemote(first.firestore)
        val secondRemote = FirebaseInventoryOperationRemote(second.firestore)
        remote.apply(userId, InventoryOperation("create", listOf(product())))
        val a = async { runCatching { remote.apply(userId, InventoryOperation("device-a", listOf(product(revision = 1, name = "A")))) } }
        val b = async { runCatching { secondRemote.apply(userId, InventoryOperation("device-b", listOf(product(revision = 1, name = "B")))) } }
        val results = listOf(a.await(), b.await())
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull().hasCause<InventoryRevisionConflict>())
        assertEquals(2L, entity(FirebaseInventoryOperationRemote.PRODUCTS, "p1").get(Source.SERVER).await().getLong("revision"))
    }

    @Test
    fun concurrentReplays_sameOperation_createsOneReceiptAndOneRevision() = firebaseEmulatorTest {
        val operation = InventoryOperation("same-op", listOf(product()))
        val a = async { remote.apply(userId, operation) }
        val b = async { remote.apply(userId, operation) }
        val results = listOf(a.await(), b.await())

        assertTrue(results.all { it.effects.single().revision == 1L })
        assertEquals(1L, entity(FirebaseInventoryOperationRemote.PRODUCTS, "p1").get(Source.SERVER).await().getLong("revision"))
        assertEquals(1, firestore.collection("users").document(userId)
            .collection(FirebaseInventoryOperationRemote.OPERATIONS).get(Source.SERVER).await().size())
    }

    @Test
    fun deleteTenItems_isOneOperation_preservesOtherFive() = firebaseEmulatorTest {
        remote.apply(userId, InventoryOperation("add15", listOf(product()) + List(15) { item("i$it") }))
        val removed = List(10) { InventoryChange(InventoryEntityType.ITEM, "i$it", 1, deleted = true) }
        val result = remote.apply(userId, InventoryOperation("delete10", removed))

        assertEquals(10, result.effects.size)
        val items = firestore.collection("users").document(userId).collection(FirebaseInventoryOperationRemote.ITEMS)
            .get(Source.SERVER).await().documents
        assertEquals(10, items.count { it.getBoolean("deleted") == true })
        assertEquals(5, items.count { it.getBoolean("deleted") == false })
    }

    @Test
    fun crossAccountWrite_isDenied() = firebaseEmulatorTest {
        assertTrue(runCatching { remote.apply("someone-else", InventoryOperation("op", listOf(product()))) }.isFailure)
    }

    @Test
    fun acknowledgementLost_newAdapterReplaysSameIdWithoutReapplyingMutation() = firebaseEmulatorTest {
        val operation = InventoryOperation("lost-ack", listOf(product()))
        remote.apply(userId, operation) // Simulate losing this acknowledgement before updating Room.
        val restarted = FirebaseInventoryOperationRemote(firestore)
        val result = withTimeout(15_000) { restarted.apply(userId, operation) }
        assertTrue(result.replayed)
        assertEquals(1L, result.effects.single().revision)
    }

    private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is T }
}
