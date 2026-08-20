package dev.pcha.foodsense.app.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs the real repository against the Firebase emulators. No hace falta prepararlos: el build
 * service `FirebaseEmulators` de `app/build.gradle.kts` los levanta antes de
 * `connectedDebugAndroidTest` y los baja al terminar.
 *
 * El assumeTrue de abajo queda como red de contención para el caso raro en que no se hayan podido
 * levantar (por ejemplo, sin `npx` en el PATH): ahí se saltean en vez de fallar.
 *
 * Nothing else covers the mapping code — the `as?` casts and defaults in `toFirestoreProduct` are
 * where a silent corruption would live, and they never run in the JVM tests.
 *
 * `runBlocking`, not `runTest`: these do real network I/O, so the virtual clock buys nothing and
 * would make `withTimeout` expire before Firestore ever answers.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseFirestoreSyncRepositoryTest {

    private lateinit var repository: FirebaseFirestoreSyncRepository
    private lateinit var userId: String

    @Before
    fun setUp() = runBlocking {
        // Sin los emuladores estos tests se saltean en vez de fallar: un servicio local apagado no
        // es una regresión, y verlo como falla tapa las que sí importan.
        assumeTrue(
            "Emuladores de Firebase no disponibles. Deberían levantarse solos con Gradle; " +
                "ver el log en app/build/firebase-emulators.log, o levantarlos a mano con " +
                "`cd firebase && npm run emulators`.",
            emulatorsReachable,
        )
        // Signing in for real: the security rules are active in the emulator and key off
        // request.auth.uid. Signing out first forces a brand new anonymous user — otherwise
        // signInAnonymously() hands back the cached one and the tests share a collection.
        auth.signOut()
        userId = auth.signInAnonymously().await().user!!.uid
        repository = FirebaseFirestoreSyncRepository(firestore)
    }

    @Test
    fun upsertProduct_newProduct_assignsIdsAndRoundTripsThroughFetchAll() = runBlocking {
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
    fun upsertProduct_existingServerId_updatesInPlace() = runBlocking {
        val stored = repository.upsertProduct(userId, product(name = "Milk"))

        repository.upsertProduct(userId, stored.copy(name = "Oat Milk"))

        val fetched = repository.fetchAll(userId).single()
        assertEquals(stored.serverId, fetched.serverId)
        assertEquals("Oat Milk", fetched.name)
    }

    @Test
    fun updateProductItems_replacesItemsWithoutClobberingName() = runBlocking {
        val stored = repository.upsertProduct(userId, product(name = "Milk", items = listOf(item(quantity = 1f))))

        repository.updateProductItems(userId, stored.serverId, listOf(item(id = "i-new", quantity = 5f)), 999L)

        val fetched = repository.fetchAll(userId).single()
        assertEquals("Milk", fetched.name) // SetOptions.merge no debe pisar el nombre
        assertEquals(listOf(5f), fetched.items.map { it.quantity })
        assertEquals(999L, fetched.updatedAt)
    }

    @Test
    fun updateProductItems_blankIds_returnsResolvedIdsMatchingWhatWasStored() = runBlocking {
        // El repositorio guarda estos ids en Room: son lo que marca a un ítem como confirmado.
        val stored = repository.upsertProduct(userId, product(name = "Milk"))

        val resolved = repository.updateProductItems(
            userId, stored.serverId, listOf(item(quantity = 1f), item(quantity = 2f)), 5L
        )

        assertTrue(resolved.all { it.id.isNotBlank() })
        assertNotEquals(resolved[0].id, resolved[1].id)
        assertEquals(resolved.map { it.id }, repository.fetchAll(userId).single().items.map { it.id })
    }

    @Test
    fun deleteProduct_removesItFromFetchAll() = runBlocking {
        val stored = repository.upsertProduct(userId, product(name = "Milk"))

        repository.deleteProduct(userId, stored.serverId)

        assertEquals(emptyList<FirestoreProduct>(), repository.fetchAll(userId))
    }

    @Test
    fun listenToChanges_remoteWrite_isEmitted() = runBlocking {
        repository.upsertProduct(userId, product(name = "Milk"))

        val emitted = withTimeout(TIMEOUT_MS) {
            repository.listenToChanges(userId).first { it.isNotEmpty() }
        }

        assertEquals("Milk", emitted.single().name)
    }

    @Test
    fun fetchAll_documentMissingOptionalFields_fallsBackToDefaults() = runBlocking {
        // Escrito sin pasar por el repositorio: un doc viejo o escrito por otra versión.
        firestore.collection("users").document(userId).collection("products").document("legacy")
            .set(mapOf("name" to "Milk", "items" to listOf(mapOf("id" to "i1"))))
            .await()

        val fetched = repository.fetchAll(userId).single()

        assertEquals(1f, fetched.items.single().quantity) // default cuando falta quantity
        assertEquals(0L, fetched.items.single().addedAt)
        assertNull(fetched.items.single().unit)
        assertEquals(0L, fetched.updatedAt)
    }

    @Test
    fun fetchAll_documentWithoutName_isDiscarded() = runBlocking {
        firestore.collection("users").document(userId).collection("products").document("nameless")
            .set(mapOf("items" to emptyList<Map<String, Any?>>()))
            .await()

        assertEquals(emptyList<FirestoreProduct>(), repository.fetchAll(userId))
    }

    @Test
    fun upsertProduct_blankItemIds_areAssignedDistinctIds() = runBlocking {
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
        private const val APP_NAME = "firestore-emulator-test"

        /** 10.0.2.2 es la IP especial para llegar al localhost del host desde el emulador. */
        private const val EMULATOR_HOST = "10.0.2.2"
        private const val FIRESTORE_PORT = 8080
        private const val AUTH_PORT = 9099
        private const val PROBE_TIMEOUT_MS = 1_500

        private lateinit var firestore: FirebaseFirestore
        private lateinit var auth: FirebaseAuth
        private var emulatorsReachable = false

        @JvmStatic
        @BeforeClass
        fun configureEmulator() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // Opciones explícitas en vez de google-services.json: ese archivo está gitignoreado, y
            // el prefijo `demo-` impide que estos tests puedan llegar a un proyecto real.
            val options = FirebaseOptions.Builder()
                .setProjectId("demo-foodsense")
                .setApplicationId("1:1:android:1")
                .setApiKey("fake-api-key")
                .build()
            val app = runCatching { FirebaseApp.getInstance(APP_NAME) }
                .getOrElse { FirebaseApp.initializeApp(context, options, APP_NAME) }
            auth = FirebaseAuth.getInstance(app).apply { useEmulator(EMULATOR_HOST, AUTH_PORT) }
            firestore = FirebaseFirestore.getInstance(app).apply {
                useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
                firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
            }
            emulatorsReachable = canConnect(FIRESTORE_PORT) && canConnect(AUTH_PORT)
        }

        private fun canConnect(port: Int) = runCatching {
            Socket().use { it.connect(InetSocketAddress(EMULATOR_HOST, port), PROBE_TIMEOUT_MS) }
        }.isSuccess
    }
}
