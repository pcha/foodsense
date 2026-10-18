package dev.pcha.foodsense.app.data.sync.outbox

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Isolated V2 protocol. Deliberately not bound in Hilt or invoked by the legacy repository.
 * All entity reads precede all writes; Firestore retries CAS conflicts inside the transaction.
 */
class FirebaseInventoryOperationRemote(private val firestore: FirebaseFirestore) : InventoryOperationRemote {
    override suspend fun apply(accountId: String, operation: InventoryOperation): InventoryOperationReceipt {
        require(accountId.isNotBlank() && '/' !in accountId)
        val fingerprint = InventoryOperationProtocol.fingerprint(operation)
        return try {
            transact(accountId, operation, fingerprint)
        } catch (error: FirebaseFirestoreException) {
            // CAS security rules can reject a racing stale commit before Firestore reports ABORTED.
            // Re-evaluate exactly once, only after an authorized server read proves revision drift.
            // A genuine denied read/write remains a permission error; never weaken the rules.
            if (error.code != FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                !hasRevisionDrift(accountId, operation)
            ) throw error
            transact(accountId, operation, fingerprint)
        }
    }

    private suspend fun hasRevisionDrift(accountId: String, operation: InventoryOperation): Boolean {
        for (change in operation.changes) {
            val snapshot = try {
                firestore.collection("users").document(accountId).collection(collection(change.type))
                    .document(change.entityId).get(Source.SERVER).await()
            } catch (_: FirebaseFirestoreException) {
                return false
            }
            val revision = snapshot.getLong("revision") ?: 0
            if (revision != change.baseRevision) return true
        }
        return false
    }

    private suspend fun transact(
        accountId: String,
        operation: InventoryOperation,
        fingerprint: String,
    ): InventoryOperationReceipt {
        val owner = firestore.collection("users").document(accountId)
        val receiptRef = owner.collection(OPERATIONS).document(operation.id)
        return firestore.runTransaction { transaction ->
            val existingReceipt = transaction.get(receiptRef)
            if (existingReceipt.exists()) {
                if (existingReceipt.getString("fingerprint") != fingerprint) {
                    throw InventoryOperationIdReused(operation.id)
                }
                return@runTransaction decodeReceipt(operation, existingReceipt, fingerprint)
            }

            val refs = operation.changes.associate { change ->
                val key = InventoryEntityKey(change.type, change.entityId)
                key to owner.collection(collection(key.type)).document(key.id)
            }
            val current = refs.mapNotNull { (key, ref) ->
                val snapshot = transaction.get(ref)
                if (snapshot.exists()) key to decodeEntity(key, snapshot) else null
            }.toMap()
            val plan = InventoryOperationProtocol.plan(operation, current)
            for (entity in plan.writes) {
                val data = entity.fields.mapValues { primitiveValue(it.value) } + mapOf(
                    "protocolVersion" to PROTOCOL_VERSION,
                    "revision" to entity.revision,
                    "deleted" to entity.deleted,
                    "operationId" to operation.id,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                transaction.set(refs.getValue(entity.key), data)
            }
            transaction.set(receiptRef, mapOf(
                "protocolVersion" to PROTOCOL_VERSION,
                "fingerprint" to fingerprint,
                "committedAt" to FieldValue.serverTimestamp(),
                "effects" to plan.effects.associate { effect ->
                    effect.key.receiptKey to mapOf(
                        "type" to effect.key.type.name,
                        "id" to effect.key.id,
                        "revision" to effect.revision,
                        "deleted" to effect.deleted,
                        "written" to effect.written,
                    )
                },
            ))
            InventoryOperationReceipt(operation.id, fingerprint, plan.effects)
        }.await()
    }

    private fun decodeEntity(key: InventoryEntityKey, snapshot: DocumentSnapshot): RemoteInventoryEntity {
        check(snapshot.getLong("protocolVersion") == PROTOCOL_VERSION) { "Unexpected inventory protocol version" }
        val revision = checkNotNull(snapshot.getLong("revision")) { "Missing inventory revision" }
        val deleted = checkNotNull(snapshot.getBoolean("deleted")) { "Missing inventory tombstone state" }
        val fields = snapshot.data.orEmpty().filterKeys { it !in METADATA_FIELDS }.mapValues { (_, value) ->
            when (value) {
                null -> JsonNull
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> error("Unsupported inventory field type")
            }
        }
        return RemoteInventoryEntity(key, revision, deleted, fields)
    }

    private fun decodeReceipt(
        operation: InventoryOperation,
        snapshot: DocumentSnapshot,
        fingerprint: String,
    ): InventoryOperationReceipt {
        check(snapshot.getLong("protocolVersion") == PROTOCOL_VERSION) { "Unexpected receipt protocol version" }
        val stored = snapshot.get("effects") as? Map<*, *> ?: error("Missing receipt effects")
        val expectedKeys = operation.changes.map { InventoryEntityKey(it.type, it.entityId) }
        check(stored.keys == expectedKeys.map { it.receiptKey }.toSet()) { "Receipt entity set mismatch" }
        val effects = expectedKeys.map { key ->
            val effect = stored[key.receiptKey] as? Map<*, *> ?: error("Invalid receipt effect")
            check(effect["type"] == key.type.name && effect["id"] == key.id)
            val revision = (effect["revision"] as? Number)?.toLong() ?: error("Invalid receipt revision")
            check(revision in 1..InventoryOperation.MAX_REVISION)
            InventoryOperationEffect(
                key, revision,
                effect["deleted"] as? Boolean ?: error("Invalid receipt tombstone"),
                effect["written"] as? Boolean ?: error("Invalid receipt write state"),
            )
        }
        // Return the original acknowledgement, even if a later operation has edited the entities.
        return InventoryOperationReceipt(operation.id, fingerprint, effects, replayed = true)
    }

    private fun primitiveValue(value: JsonElement): Any? = when {
        value == JsonNull -> null
        value is JsonPrimitive && value.isString -> value.content
        value is JsonPrimitive && value.longOrNull != null -> value.long
        value is JsonPrimitive && value.doubleOrNull != null -> value.double
        else -> error("Unsupported inventory payload field")
    }

    companion object {
        const val PROTOCOL_VERSION = 1L
        const val PRODUCTS = "inventory_products"
        const val ITEMS = "inventory_items"
        const val OPERATIONS = "inventory_operations"
        private val METADATA_FIELDS = setOf("protocolVersion", "revision", "deleted", "operationId", "updatedAt")

        fun collection(type: InventoryEntityType) = when (type) {
            InventoryEntityType.PRODUCT -> PRODUCTS
            InventoryEntityType.ITEM -> ITEMS
        }
    }
}
