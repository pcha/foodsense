package dev.pcha.foodsense.app.data.sync.outbox

import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseInventoryFailureMappingTest {
    private fun failure(code: FirebaseFirestoreException.Code) =
        FirebaseFirestoreException("boom", code).asInventoryFailure()

    @Test
    fun availabilityFailures_areRetryable() {
        for (code in listOf(
            FirebaseFirestoreException.Code.UNAVAILABLE,
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
            FirebaseFirestoreException.Code.INTERNAL,
            FirebaseFirestoreException.Code.CANCELLED,
            FirebaseFirestoreException.Code.UNKNOWN,
            FirebaseFirestoreException.Code.UNAUTHENTICATED,
        )) {
            val mapped = failure(code)
            assertTrue("$code should be transient but was $mapped", mapped is InventoryTransientFailure)
            assertEquals(code.name, (mapped as InventoryTransientFailure).code)
        }
    }

    @Test
    fun authorizationAndRejectionFailures_arePermanent() {
        for (code in listOf(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.INVALID_ARGUMENT,
            FirebaseFirestoreException.Code.FAILED_PRECONDITION,
            FirebaseFirestoreException.Code.NOT_FOUND,
            FirebaseFirestoreException.Code.ALREADY_EXISTS,
        )) {
            val mapped = failure(code)
            assertTrue("$code should be permanent but was $mapped", mapped is InventoryPermanentFailure)
            assertEquals(code.name, (mapped as InventoryPermanentFailure).code)
        }
    }

    @Test
    fun mappedFailure_keepsTheOriginalCauseForDiagnostics() {
        val original = FirebaseFirestoreException("boom", FirebaseFirestoreException.Code.UNAVAILABLE)
        assertEquals(original, original.asInventoryFailure().cause)
    }
}
