package dev.pcha.foodsense.app.data.sync.outbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** An immutable intent, not a snapshot of the entire inventory. Wire format is versioned separately. */
@Serializable
data class InventoryOperation(
    val id: String,
    val changes: List<InventoryChange>,
) {
    fun validate() {
        require(validId(id))
        require(changes.size in 1..MAX_CHANGES)
        require(changes.map { it.type to it.entityId }.distinct().size == changes.size)
        changes.forEach { change ->
            require(validId(change.entityId))
            require(change.baseRevision in 0 until MAX_REVISION)
            require(change.fields.keys.none { it in RESERVED_FIELDS })
            require(!change.deleted || change.fields.isEmpty()) { "Deletion cannot also edit fields" }
        }
    }

    fun encode(): String {
        validate()
        return Json.encodeToString(serializer(), this).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Operation payload too large" }
        }
    }

    companion object {
        const val PAYLOAD_VERSION = 1
        // Bound the atomic action; never silently split it into independently visible commits.
        const val MAX_CHANGES = 100
        const val MAX_PAYLOAD_BYTES = 128 * 1024
        const val MAX_REVISION = 9_007_199_254_740_991L
        private val RESERVED_FIELDS = setOf("protocolVersion", "revision", "deleted", "updatedAt", "operationId")

        private fun validId(id: String) = id.isNotBlank() && id.length <= 200 &&
            '/' !in id && id != "." && id != ".." && !id.startsWith("__")

        fun decode(payload: String, version: Int): InventoryOperation {
            require(version == PAYLOAD_VERSION) { "Unsupported inventory operation payload version: $version" }
            return Json.decodeFromString(serializer(), payload).also { it.validate() }
        }
    }
}

@Serializable
enum class InventoryEntityType { PRODUCT, ITEM }

@Serializable
data class InventoryChange(
    val type: InventoryEntityType,
    val entityId: String,
    val baseRevision: Long,
    val fields: Map<String, JsonElement> = emptyMap(),
    val deleted: Boolean = false,
)
