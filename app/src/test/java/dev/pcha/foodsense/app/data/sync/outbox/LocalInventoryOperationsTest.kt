package dev.pcha.foodsense.app.data.sync.outbox

import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import dev.pcha.foodsense.app.data.local.database.newEntityId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LocalInventoryOperationsTest {

    private fun product(name: String = "Apples", revision: Long = 0, entityId: String = "p-$name") =
        ProductEntity(name = name, entityId = entityId, revision = revision).also { it.uid = 1 }

    private fun item(
        product: ProductEntity,
        revision: Long = 0,
        entityId: String = newEntityId(),
        quantity: Float = 1f,
    ) = ItemEntity(
        productId = product.uid,
        quantity = quantity,
        unit = null,
        expirationDate = null,
        addedAt = LocalDate.now(),
        entityId = entityId,
        revision = revision,
    )

    @Test
    fun productTheServerNeverSaw_travelsWithItsFirstItems_createdFirst() {
        val product = product()
        val items = List(15) { item(product) }

        val operation = LocalInventoryOperations.itemsChanged(listOf(product), items).single()

        operation.validate()
        val productChange = operation.changes.first()
        assertEquals(InventoryEntityType.PRODUCT, productChange.type)
        assertEquals(product.entityId, productChange.entityId)
        assertEquals(0L, productChange.baseRevision)
        assertEquals(setOf("name"), productChange.fields.keys)
        assertEquals(JsonPrimitive("Apples"), productChange.fields["name"])
        assertEquals(15, operation.changes.drop(1).size)
        assertTrue(operation.changes.drop(1).all {
            it.type == InventoryEntityType.ITEM && it.baseRevision == 0L && !it.deleted
        })
        // Every item points at the product by its V2 entity id, which is why the product has to be there.
        assertTrue(operation.changes.drop(1).all {
            it.fields["productId"]?.jsonPrimitive?.content == product.entityId
        })
    }

    @Test
    fun productAlreadyOnTheServer_travelsOnlyWithItsItems() {
        val product = product(revision = 3)

        val changes = LocalInventoryOperations.itemsChanged(listOf(product), listOf(item(product, revision = 3)))
            .single()
            .changes

        assertEquals(listOf(InventoryEntityType.ITEM), changes.map { it.type })
        assertEquals(3L, changes.single().baseRevision)
    }

    @Test
    fun deletions_carryNoFieldsAndReserveTheRevisionTheServerKnows() {
        val product = product(revision = 2)

        val change = LocalInventoryOperations.itemsDeleted(listOf(item(product, revision = 2, entityId = "i1")))
            .single()
            .changes
            .single()

        assertEquals(InventoryEntityType.ITEM, change.type)
        assertEquals("i1", change.entityId)
        assertEquals(2L, change.baseRevision)
        assertTrue(change.deleted)
        assertTrue(change.fields.isEmpty())
    }

    @Test
    fun deletingTheLastItemOfAProduct_tombstonesTheProductAfterIt() {
        val product = product(revision = 2)

        val changes = LocalInventoryOperations
            .itemsDeleted(listOf(item(product, revision = 2, entityId = "i1")), listOf(product))
            .single()
            .changes

        assertEquals(listOf(InventoryEntityType.ITEM, InventoryEntityType.PRODUCT), changes.map { it.type })
        assertEquals(product.entityId, changes.last().entityId)
        assertEquals(2L, changes.last().baseRevision)
        assertTrue(changes.all { it.deleted && it.fields.isEmpty() })
    }

    @Test
    fun deletingSomeItemsOfAProduct_leavesTheProductAlone() {
        val product = product(revision = 2)

        val changes = LocalInventoryOperations
            .itemsDeleted(listOf(item(product, revision = 2, entityId = "i1")))
            .single()
            .changes

        assertEquals(listOf(InventoryEntityType.ITEM), changes.map { it.type })
    }

    @Test
    fun rename_carriesTheProductEvenWhenTheServerAlreadyKnowsIt() {
        val known = product(name = "Pears", revision = 4)

        val change = LocalInventoryOperations.productChanged(known).single().changes.single()

        assertEquals(InventoryEntityType.PRODUCT, change.type)
        assertEquals(4L, change.baseRevision)
        assertFalse(change.deleted)
        assertEquals(JsonPrimitive("Pears"), change.fields["name"])
    }

    @Test
    fun edit_keepsTheBaseRevisionOfTheRowItDescribes() {
        val product = product(revision = 1)

        val change = LocalInventoryOperations
            .itemsChanged(listOf(product), listOf(item(product, revision = 1, quantity = 2f)))
            .single()
            .changes
            .single()

        assertEquals(1L, change.baseRevision)
        assertEquals(JsonPrimitive(2.0), change.fields["quantity"])
    }

    @Test
    fun anActionAtTheRulesLimit_isOneIntent() {
        val product = product(revision = 1)

        val operations = LocalInventoryOperations.itemsChanged(listOf(product), List(100) { item(product) })

        assertEquals(1, operations.size)
        assertEquals(InventoryOperation.MAX_CHANGES, operations.single().changes.size)
    }

    @Test
    fun anActionBeyondTheRulesLimit_isSplitInOrder() {
        val product = product(revision = 1)
        val items = List(101) { item(product) }

        val operations = LocalInventoryOperations.itemsChanged(listOf(product), items)

        assertEquals(2, operations.size)
        assertEquals(listOf(100, 1), operations.map { it.changes.size })
        operations.forEach { it.validate() }
        // Order is what the FIFO drain relies on: the whole action still arrives, in this order.
        assertEquals(items.map { it.entityId }, operations.flatMap { operation -> operation.changes.map { it.entityId } })
        assertNotEquals(operations[0].id, operations[1].id)
    }

    @Test
    fun aProductCreation_leadsTheFirstOfSeveralIntents() {
        val product = product()
        val items = List(100) { item(product) }

        val operations = LocalInventoryOperations.itemsChanged(listOf(product), items)

        assertEquals(2, operations.size)
        // 1 product + 100 items = 101 changes: the product fills the first slot of the first intent.
        assertEquals(InventoryEntityType.PRODUCT, operations[0].changes.first().type)
        assertEquals(product.entityId, operations[0].changes.first().entityId)
        assertEquals(100, operations[0].changes.size)
        assertEquals(1, operations[1].changes.size)
        assertEquals(InventoryEntityType.ITEM, operations[1].changes.single().type)
    }

    @Test
    fun everyIntent_fitsOneReceiptAndOnePayload() {
        val product = product(revision = 1)

        val operations = LocalInventoryOperations.itemsChanged(listOf(product), List(250) { item(product) })

        assertEquals(3, operations.size)
        assertTrue(operations.all { it.changes.size <= InventoryOperation.MAX_CHANGES })
        // encode() rejects a payload over MAX_PAYLOAD_BYTES, so this also covers the wire-size bound.
        operations.forEach { it.encode() }
        assertEquals(250, operations.sumOf { it.changes.size })
    }

    @Test
    fun deletingAProduct_tombstonesItsItemsBeforeTheProduct() {
        val product = product(revision = 2)
        val items = List(3) { item(product, revision = 2) }

        val changes = LocalInventoryOperations.productDeleted(product, items).single().changes

        assertEquals(4, changes.size)
        assertTrue(changes.all { it.deleted && it.fields.isEmpty() })
        assertEquals(listOf(InventoryEntityType.ITEM, InventoryEntityType.ITEM, InventoryEntityType.ITEM,
            InventoryEntityType.PRODUCT), changes.map { it.type })
        assertEquals(product.entityId, changes.last().entityId)
        assertEquals(2L, changes.last().baseRevision)
    }

    @Test
    fun deletingAProductWithManyItems_leavesTheProductInTheLastIntent() {
        val product = product(revision = 1)
        val items = List(100) { item(product, revision = 1) }

        val operations = LocalInventoryOperations.productDeleted(product, items)

        assertEquals(2, operations.size)
        assertEquals(listOf(100, 1), operations.map { it.changes.size })
        assertEquals(product.entityId, operations.last().changes.last().entityId)
        assertEquals(InventoryEntityType.PRODUCT, operations.last().changes.last().type)
    }

    @Test
    fun anActionThatChangedNothing_isNotAnIntent() {
        val known = product(revision = 1)

        assertTrue(LocalInventoryOperations.itemsChanged(listOf(known), emptyList()).isEmpty())
        assertTrue(LocalInventoryOperations.itemsDeleted(emptyList()).isEmpty())
    }

    @Test
    fun aRepeatedRow_isOneEntityWithOneChange() {
        val product = product(revision = 1)
        val item = item(product, entityId = "i1")

        val changes = LocalInventoryOperations.itemsChanged(listOf(product), listOf(item, item)).single().changes

        assertEquals(listOf("i1"), changes.map { it.entityId })
    }

    @Test
    fun anItemWithoutItsProductRow_cannotBeDescribed() {
        val product = product()
        val orphan = ItemEntity(
            productId = 99,
            quantity = 1f,
            unit = null,
            expirationDate = null,
            addedAt = LocalDate.now(),
        )

        assertTrue(LocalInventoryOperations.itemsChanged(listOf(product), listOf(orphan)).isEmpty())
    }

    @Test
    fun everyIntent_getsItsOwnOperationId() {
        val product = product(revision = 1)
        val item = item(product)

        val first = LocalInventoryOperations.itemsChanged(listOf(product), listOf(item)).single()
        val second = LocalInventoryOperations.itemsChanged(listOf(product), listOf(item)).single()

        assertNotEquals(first.id, second.id)
    }
}
