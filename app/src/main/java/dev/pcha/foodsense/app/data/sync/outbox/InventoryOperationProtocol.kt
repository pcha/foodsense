package dev.pcha.foodsense.app.data.sync.outbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** SDK-independent protocol types; not exposed to UI. Revisions are per entity, not per snapshot. */
data class InventoryEntityKey(val type: InventoryEntityType, val id: String) {
    val receiptKey: String get() = "${type.name}:$id"
}

data class RemoteInventoryEntity(
    val key: InventoryEntityKey,
    val revision: Long,
    val deleted: Boolean,
    val fields: Map<String, JsonElement>,
)

data class InventoryOperationEffect(
    val key: InventoryEntityKey,
    val revision: Long,
    val deleted: Boolean,
    val written: Boolean,
)

data class InventoryOperationReceipt(
    val operationId: String,
    val fingerprint: String,
    val effects: List<InventoryOperationEffect>,
    val replayed: Boolean = false,
)

enum class InventoryConflictReason { REVISION_MISMATCH, ENTITY_DELETED }

class InventoryRevisionConflict(
    val entity: InventoryEntityKey,
    val expected: Long,
    val actual: Long,
    val reason: InventoryConflictReason,
) : IllegalStateException("Inventory conflict: ${entity.receiptKey} expected=$expected actual=$actual reason=$reason")

class InventoryOperationIdReused(operationId: String) :
    IllegalStateException("Operation ID reused with a different payload: $operationId")

/**
 * Retryable transport/availability failure. The intent must stay pending and keep its place in the
 * FIFO queue; retrying it later is safe because the remote deduplicates by operation ID.
 */
class InventoryTransientFailure(val code: String, cause: Throwable? = null) :
    Exception("Inventory remote temporarily unavailable ($code)", cause)

/**
 * Non-retryable failure (authorization, malformed intent, rejected request). Retrying unchanged would
 * fail again, so the intent is blocked for later resolution instead of being dropped or retried blindly.
 */
class InventoryPermanentFailure(val code: String, cause: Throwable? = null) :
    Exception("Inventory remote rejected the operation ($code)", cause)

interface InventoryOperationRemote {
    /** Network-dependent transaction. Caller must retain the local intent until server acknowledgement. */
    suspend fun apply(accountId: String, operation: InventoryOperation): InventoryOperationReceipt
}

data class InventoryOperationPlan(
    val writes: List<RemoteInventoryEntity>,
    val effects: List<InventoryOperationEffect>,
)

/** Pure conflict policy shared by the Firestore adapter and deterministic concurrency tests. */
object InventoryOperationProtocol {
    fun fingerprint(operation: InventoryOperation): String {
        operation.validate()
        val stable = operation.copy(changes = operation.changes.sortedWith(compareBy({ it.type.name }, { it.entityId })))
        fun canonical(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(element.map(::canonical))
            else -> element
        }
        val json = canonical(Json.encodeToJsonElement(InventoryOperation.serializer(), stable)).toString()
        require(json.toByteArray(Charsets.UTF_8).size <= InventoryOperation.MAX_PAYLOAD_BYTES) { "Operation payload too large" }
        return MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun plan(operation: InventoryOperation, current: Map<InventoryEntityKey, RemoteInventoryEntity>): InventoryOperationPlan {
        operation.validate()
        val writes = mutableListOf<RemoteInventoryEntity>()
        val effects = operation.changes.map { change ->
            val key = InventoryEntityKey(change.type, change.entityId)
            val existing = current[key]
            val revision = existing?.revision ?: 0
            require(revision in 0 until InventoryOperation.MAX_REVISION)
            if (change.baseRevision > revision || (!change.deleted && change.baseRevision != revision)) {
                throw InventoryRevisionConflict(key, change.baseRevision, revision, InventoryConflictReason.REVISION_MISMATCH)
            }
            if (existing?.deleted == true) {
                if (!change.deleted) {
                    throw InventoryRevisionConflict(key, change.baseRevision, revision, InventoryConflictReason.ENTITY_DELETED)
                }
                // Deleting an already-deleted item succeeds, without incrementing revision again.
                InventoryOperationEffect(key, revision, deleted = true, written = false)
            } else {
                val fields = existing?.fields.orEmpty() + change.fields
                if (!change.deleted) validateFields(change.type, fields)
                val updated = RemoteInventoryEntity(key, revision + 1, change.deleted, fields)
                writes += updated
                InventoryOperationEffect(key, updated.revision, updated.deleted, written = true)
            }
        }
        return InventoryOperationPlan(writes, effects)
    }

    private fun validateFields(type: InventoryEntityType, fields: Map<String, JsonElement>) {
        when (type) {
            InventoryEntityType.PRODUCT -> {
                require(fields.keys == setOf("name")) { "Product requires only name" }
                require((fields["name"] as? JsonPrimitive)?.isString == true)
                require(fields.getValue("name").jsonPrimitive.content.isNotBlank())
                require(fields.getValue("name").jsonPrimitive.content.length <= 200)
            }
            InventoryEntityType.ITEM -> {
                require(fields.keys == setOf("productId", "quantity", "unit", "expirationDate", "addedAt"))
                val product = fields.getValue("productId") as? JsonPrimitive
                require(product?.isString == true && product.content.isNotBlank() && '/' !in product.content)
                val quantity = fields.getValue("quantity") as? JsonPrimitive
                require(quantity?.isString == false && quantity.doubleOrNull?.let { it.isFinite() && it > 0 } == true)
                val unit = fields.getValue("unit")
                require(unit == JsonNull || (unit is JsonPrimitive && unit.isString && unit.content in setOf("L", "ML", "G", "KG")))
                for (field in listOf("expirationDate", "addedAt")) {
                    val value = fields.getValue(field)
                    require((field == "expirationDate" && value == JsonNull) ||
                        (value is JsonPrimitive && !value.isString && value.longOrNull != null))
                }
            }
        }
    }
}
