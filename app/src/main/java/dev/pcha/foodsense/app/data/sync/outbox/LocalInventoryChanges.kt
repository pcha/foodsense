package dev.pcha.foodsense.app.data.sync.outbox

import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure bridge from local Room rows to versioned protocol changes. [LocalInventoryOperations] composes
 * these into intents, so the field contract between Room and the V2 wire format is defined and tested
 * in one place, instead of being discovered later by a rules rejection.
 *
 * `baseRevision` always comes from the row's known revision, never from a device clock.
 */
object LocalInventoryChanges {
    fun product(entity: ProductEntity): InventoryChange = InventoryChange(
        type = InventoryEntityType.PRODUCT,
        entityId = entity.entityId,
        baseRevision = entity.revision,
        fields = mapOf("name" to JsonPrimitive(entity.name)),
    )

    fun productDeletion(entity: ProductEntity): InventoryChange = InventoryChange(
        type = InventoryEntityType.PRODUCT,
        entityId = entity.entityId,
        baseRevision = entity.revision,
        deleted = true,
    )

    fun item(entity: ItemEntity, productEntityId: String): InventoryChange = InventoryChange(
        type = InventoryEntityType.ITEM,
        entityId = entity.entityId,
        baseRevision = entity.revision,
        fields = itemFields(entity, productEntityId),
    )

    fun itemDeletion(entity: ItemEntity): InventoryChange = InventoryChange(
        type = InventoryEntityType.ITEM,
        entityId = entity.entityId,
        baseRevision = entity.revision,
        deleted = true,
    )

    private fun itemFields(entity: ItemEntity, productEntityId: String): Map<String, JsonElement> = mapOf(
        "productId" to JsonPrimitive(productEntityId),
        "quantity" to JsonPrimitive(entity.quantity.toDouble()),
        "unit" to (entity.unit?.let { JsonPrimitive(it.name) } ?: JsonNull),
        "expirationDate" to (entity.expirationDate?.let { JsonPrimitive(it.toEpochDay()) } ?: JsonNull),
        "addedAt" to JsonPrimitive(entity.addedAt.toEpochDay()),
    )
}
