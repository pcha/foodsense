package dev.pcha.foodsense.app.data.sync.outbox

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InventoryOperationProtocolTest {
    private val key = InventoryEntityKey(InventoryEntityType.PRODUCT, "p1")
    private fun product(revision: Long = 1, deleted: Boolean = false) = RemoteInventoryEntity(
        key, revision, deleted, mapOf("name" to JsonPrimitive("Apples")),
    )
    private fun rename(base: Long, name: String = "Pears") = InventoryOperation("rename", listOf(
        InventoryChange(key.type, key.id, base, mapOf("name" to JsonPrimitive(name))),
    ))
    private fun delete(base: Long) = InventoryOperation("delete", listOf(
        InventoryChange(key.type, key.id, base, deleted = true),
    ))

    @Test
    fun create_missingEntity_startsAtRevisionOne() {
        val plan = InventoryOperationProtocol.plan(rename(0), emptyMap())
        assertEquals(1L, plan.writes.single().revision)
        assertEquals(JsonPrimitive("Pears"), plan.writes.single().fields["name"])
    }

    @Test
    fun edit_matchingRevision_incrementsRevision() {
        val plan = InventoryOperationProtocol.plan(rename(7), mapOf(key to product(7)))
        assertEquals(8L, plan.writes.single().revision)
    }

    @Test
    fun edit_staleRevision_reportsConflictWithoutAPlan() {
        val error = runCatching { InventoryOperationProtocol.plan(rename(6), mapOf(key to product(7))) }.exceptionOrNull()
        assertTrue(error is InventoryRevisionConflict)
        error as InventoryRevisionConflict
        assertEquals(6L, error.expected)
        assertEquals(7L, error.actual)
    }

    @Test
    fun edit_futureRevision_isRejected() {
        assertTrue(runCatching { InventoryOperationProtocol.plan(rename(8), mapOf(key to product(7))) }.exceptionOrNull() is InventoryRevisionConflict)
    }

    @Test
    fun delete_staleRevision_winsOverConcurrentEdit() {
        val plan = InventoryOperationProtocol.plan(delete(6), mapOf(key to product(7)))
        assertTrue(plan.writes.single().deleted)
        assertEquals(8L, plan.writes.single().revision)
    }

    @Test
    fun edit_deletedEntity_cannotResurrectIt() {
        val error = runCatching { InventoryOperationProtocol.plan(rename(7), mapOf(key to product(7, true))) }.exceptionOrNull()
        assertEquals(InventoryConflictReason.ENTITY_DELETED, (error as InventoryRevisionConflict).reason)
    }

    @Test
    fun delete_alreadyDeleted_isSuccessfulWithoutAnotherWrite() {
        val plan = InventoryOperationProtocol.plan(delete(6), mapOf(key to product(7, true)))
        assertTrue(plan.writes.isEmpty())
        assertEquals(InventoryOperationEffect(key, 7, deleted = true, written = false), plan.effects.single())
    }

    @Test
    fun delete_missingEntity_recordsTombstoneAgainstFutureLateCreation() {
        val plan = InventoryOperationProtocol.plan(delete(0), emptyMap())
        assertEquals(1L, plan.writes.single().revision)
        assertTrue(plan.writes.single().deleted)
        assertTrue(runCatching { InventoryOperationProtocol.plan(rename(0), plan.writes.associateBy { it.key }) }.exceptionOrNull() is InventoryRevisionConflict)
    }

    @Test
    fun fingerprint_reorderedFieldsAndEntities_remainsStable() {
        val fields = mapOf("quantity" to JsonPrimitive(1), "productId" to JsonPrimitive("p1"))
        val first = InventoryOperation("op", listOf(
            InventoryChange(InventoryEntityType.ITEM, "i1", 0, fields),
            InventoryChange(InventoryEntityType.PRODUCT, "p1", 0, mapOf("name" to JsonPrimitive("Apples"))),
        ))
        val second = first.copy(changes = first.changes.reversed().map { it.copy(fields = it.fields.toSortedMap()) })
        assertEquals(InventoryOperationProtocol.fingerprint(first), InventoryOperationProtocol.fingerprint(second))
        assertNotEquals(InventoryOperationProtocol.fingerprint(first), InventoryOperationProtocol.fingerprint(first.copy(id = "other")))
    }

    @Test
    fun fingerprint_changedPayload_doesNotMatchOriginal() {
        assertNotEquals(InventoryOperationProtocol.fingerprint(rename(1)), InventoryOperationProtocol.fingerprint(rename(1, "Other")))
    }

    @Test
    fun atomicAction_aboveLimit_isRejectedNotSplit() {
        val operation = InventoryOperation("too-many", List(InventoryOperation.MAX_CHANGES + 1) {
            InventoryChange(InventoryEntityType.ITEM, "i$it", 0, deleted = true)
        })
        assertTrue(runCatching { operation.validate() }.isFailure)
    }

    @Test
    fun payload_aboveByteLimit_isRejectedBeforeItCanBeEnqueued() {
        val oversized = rename(0, "a".repeat(InventoryOperation.MAX_PAYLOAD_BYTES))
        assertTrue(runCatching { oversized.encode() }.isFailure)
        assertTrue(runCatching { InventoryOperationProtocol.fingerprint(oversized) }.isFailure)
    }

    @Test
    fun liveItem_incompleteOrInvalidFields_isRejected() {
        val itemKey = InventoryEntityKey(InventoryEntityType.ITEM, "i1")
        val invalid = InventoryOperation("op", listOf(InventoryChange(itemKey.type, itemKey.id, 0, mapOf("quantity" to JsonPrimitive(6)))))
        assertTrue(runCatching { InventoryOperationProtocol.plan(invalid, emptyMap()) }.isFailure)
    }
}
