package dev.pcha.foodsense.app.data.sync.outbox

import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity

/**
 * Builds the versioned intents for one complete local action from the rows the caller read inside the
 * same transaction, so every `baseRevision` is the revision this device knows at that moment.
 *
 * An action can need more than one intent: [InventoryOperation.MAX_CHANGES] matches the security rules,
 * which accept a receipt of at most 100 effects. A larger action is split in order instead of dropped.
 * The local commit stays atomic, and each intent is an independent, idempotent unit that FIFO delivery
 * applies in sequence, so another device can see the action partially applied for a moment and then
 * converge. Leaving it unqueued would be worse: reconciliation pulls server state back into Room, so a
 * change the server never received would be silently reverted.
 *
 * A product travels with its items while [ProductEntity.revision] is 0, which the row itself documents
 * as "not created on the V2 server yet": an item references its product by entity id, so the product
 * document has to be created first. Deletion runs the other way, items then product, so no item is left
 * pointing at a product that is already gone.
 */
object LocalInventoryOperations {

    /** Adds or edits [items], each of them belonging to one of [products]. */
    fun itemsChanged(products: List<ProductEntity>, items: List<ItemEntity>): List<InventoryOperation> {
        val byUid = products.associateBy { it.uid }
        val itemChanges = ArrayList<InventoryChange>(items.size)
        for (item in items) {
            // An item whose product row was not handed over cannot be described: productId is a required
            // wire field. Queueing the rest would put half a user action on the wire, so nothing travels.
            val product = byUid[item.productId] ?: return emptyList()
            itemChanges += LocalInventoryChanges.item(item, product.entityId)
        }
        val creations = products.filter { it.revision == 0L }.map(LocalInventoryChanges::product)
        return operations(creations + itemChanges)
    }

    /**
     * Removes [items], and the products in [emptiedProducts] that this action leaves without items.
     *
     * Deleting a product's last item is a product deletion in this app: the UI hides empty products and
     * the legacy sender deletes the product document, so V2 has to tombstone it too or it keeps an empty
     * product forever. The product changes come last, after the item tombstones that made them empty.
     */
    fun itemsDeleted(items: List<ItemEntity>, emptiedProducts: List<ProductEntity> = emptyList()): List<InventoryOperation> =
        operations(items.map(LocalInventoryChanges::itemDeletion) + emptiedProducts.map(LocalInventoryChanges::productDeletion))

    /** Removes a product together with [items], items first so none is orphaned on the server. */
    fun productDeleted(product: ProductEntity, items: List<ItemEntity>): List<InventoryOperation> =
        operations(items.map(LocalInventoryChanges::itemDeletion) + LocalInventoryChanges.productDeletion(product))

    /**
     * Creates the product on the V2 server if it is not there yet, or renames it. Always carries the
     * product change: a rename is a change to a product the device may still have to introduce.
     */
    fun productChanged(product: ProductEntity): List<InventoryOperation> =
        listOf(InventoryOperation(newOperationId(), listOf(LocalInventoryChanges.product(product))))

    private fun operations(changes: List<InventoryChange>): List<InventoryOperation> {
        // The protocol rejects a repeated (type, entityId) pair: one row is one entity, so the first
        // change for it wins instead of failing the user's action.
        val distinct = changes.distinctBy { it.type to it.entityId }
        return distinct.chunked(InventoryOperation.MAX_CHANGES)
            .map { InventoryOperation(newOperationId(), it) }
    }
}
