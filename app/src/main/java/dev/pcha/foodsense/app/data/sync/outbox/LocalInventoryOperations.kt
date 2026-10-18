package dev.pcha.foodsense.app.data.sync.outbox

import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity

/**
 * Builds the versioned intent for one complete local action from the rows the caller read inside the
 * same transaction, so every `baseRevision` is the revision this device knows at that moment.
 *
 * A product travels with its items while [ProductEntity.revision] is 0, which the row itself documents
 * as "not created on the V2 server yet": an item references its product by entity id, so the product
 * document has to be created by the same intent. Once this device has queued that create, the revision
 * bumped at enqueue (see `InventoryOutbox.recordCommitted`) keeps later intents from repeating it.
 *
 * Returns null when the action cannot travel as a single intent - more than
 * [InventoryOperation.MAX_CHANGES] changes, or no change at all. Splitting it would let another device
 * observe half of one user action, so the action stays unqueued instead: the local commit is unaffected
 * and `InventoryIntentRecorder` reports the gap.
 */
object LocalInventoryOperations {

    /** Adds or edits [items], each of them belonging to one of [products]. */
    fun itemsChanged(products: List<ProductEntity>, items: List<ItemEntity>): InventoryOperation? {
        val byUid = products.associateBy { it.uid }
        val itemChanges = ArrayList<InventoryChange>(items.size)
        for (item in items) {
            // An item whose product row was not handed over cannot be described: productId is a required
            // wire field. Queueing the rest would put half a user action on the wire, so nothing travels.
            val product = byUid[item.productId] ?: return null
            itemChanges += LocalInventoryChanges.item(item, product.entityId)
        }
        val creations = products.filter { it.revision == 0L }.map(LocalInventoryChanges::product)
        return operation(creations + itemChanges)
    }

    /** Removes [items]. A deletion carries no fields, so it needs no product document to exist. */
    fun itemsDeleted(items: List<ItemEntity>): InventoryOperation? =
        operation(items.map(LocalInventoryChanges::itemDeletion))

    /**
     * Creates the product on the V2 server if it is not there yet, or renames it. Always carries the
     * product change: a rename is a change to a product the device may still have to introduce.
     */
    fun productChanged(product: ProductEntity): InventoryOperation =
        checkNotNull(operation(listOf(LocalInventoryChanges.product(product)))) {
            "A single product change always fits one intent"
        }

    private fun operation(changes: List<InventoryChange>): InventoryOperation? {
        // The protocol rejects a repeated (type, entityId) pair: one row is one entity, so the first
        // change for it wins instead of failing the user's action.
        val distinct = changes.distinctBy { it.type to it.entityId }
        if (distinct.isEmpty() || distinct.size > InventoryOperation.MAX_CHANGES) return null
        return InventoryOperation(newOperationId(), distinct)
    }
}
