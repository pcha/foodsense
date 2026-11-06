package dev.pcha.foodsense.app.data.sync

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** A fresh authenticated SDK client per test; no cached users, listeners or channels cross tests. */
internal class FirebaseEmulatorSession private constructor(
    private val app: FirebaseApp,
    val auth: FirebaseAuth,
    val firestore: FirebaseFirestore,
) {
    val userId: String get() = checkNotNull(auth.currentUser).uid

    suspend fun close() {
        try {
            withTimeout(CLEANUP_TIMEOUT_MS) { firestore.terminate().await() }
        } finally {
            auth.signOut()
            app.delete()
        }
    }

    companion object {
        private const val CLEANUP_TIMEOUT_MS = 5_000L

        suspend fun open(
            authenticate: suspend (FirebaseAuth) -> Unit = { it.signInAnonymously().await(); Unit },
        ): FirebaseEmulatorSession {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val options = FirebaseOptions.Builder().setProjectId("demo-foodsense")
                .setApplicationId("1:1:android:1").setApiKey("fake-api-key").build()
            val app = FirebaseApp.initializeApp(context, options, "inventory-test-${UUID.randomUUID()}")
            val auth = FirebaseAuth.getInstance(app).apply { useEmulator("10.0.2.2", 9099) }
            val firestore = FirebaseFirestore.getInstance(app).apply {
                useEmulator("10.0.2.2", 8080)
                firestoreSettings = firestoreSettings { setLocalCacheSettings(memoryCacheSettings {}) }
            }
            val session = FirebaseEmulatorSession(app, auth, firestore)
            try {
                authenticate(auth)
                return session
            } catch (error: Throwable) {
                // Task.await cancellation does not cancel the underlying SDK operation. Dispose the
                // client even when setup fails, without replacing the original diagnostic.
                try {
                    session.close()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }
}

private const val TEST_TIMEOUT_MS = 30_000L

/** Bound setup, each test body and teardown; emit useful stacks rather than waiting for Gradle's kill. */
internal fun <T> firebaseEmulatorTest(block: suspend CoroutineScope.() -> T): T = runBlocking {
    try {
        withTimeout(TEST_TIMEOUT_MS, block)
    } catch (error: TimeoutCancellationException) {
        Thread.getAllStackTraces().forEach { (thread, trace) ->
            Log.e("FirebaseEmulatorTest", "Timeout thread=${thread.name} state=${thread.state}\n${trace.joinToString("\n")}")
        }
        throw AssertionError(
            "Firebase emulator test exceeded ${TEST_TIMEOUT_MS}ms; inspect FirebaseEmulatorTest logcat and firebase-emulators.log",
            error,
        )
    }
}
