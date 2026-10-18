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
        require(id.isNotBlank() && '/' !in id)
        require(changes.isNotEmpty())
        require(changes.map { it.type to it.entityId }.distinct().size == changes.size)
        changes.forEach { change ->
            require(change.entityId.isNotBlank() && '/' !in change.entityId)
            require(change.baseRevision >= 0)
            require(change.fields.keys.none { it in RESERVED_FIELDS })
        }
    }

    fun encode(): String {
        validate()
        return Json.encodeToString(serializer(), this)
    }

    companion object {
        const val PAYLOAD_VERSION = 1
        private val RESERVED_FIELDS = setOf("revision", "deleted", "updatedAt", "operationId")

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
