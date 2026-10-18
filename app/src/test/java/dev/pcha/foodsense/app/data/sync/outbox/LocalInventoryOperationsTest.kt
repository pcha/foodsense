package dev.pcha.foodsense.app.data.sync.outbox

import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import dev.pcha.foodsense.app.data.local.database.newEntityId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

        val operation = LocalInventoryOperations.itemsChanged(listOf(product), items)!!

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

        val changes = LocalInventoryOperations.itemsChanged(listOf(product), listOf(item(product, revision = 3)))!!.changes

        assertEquals(listOf(InventoryEntityType.ITEM), changes.map { it.type })
        assertEquals(3L, changes.single().baseRevision)
    }

    @Test
    fun deletions_carryNoFieldsAndReserveTheRevisionTheServerKnows() {
        val product = product(revision = 2)

        val change = LocalInventoryOperations.itemsDeleted(listOf(item(product, revision = 2, entityId = "i1")))!!
            .changes.single()

        assertEquals(InventoryEntityType.ITEM, change.type)
        assertEquals("i1", change.entityId)
        assertEquals(2L, change.baseRevision)
        assertTrue(change.deleted)
        assertTrue(change.fields.isEmpty())
    }

    @Test
    fun rename_carriesTheProductEvenWhenTheServerAlreadyKnowsIt() {
        val known = product(name = "Pears", revision = 4)

        val change = LocalInventoryOperations.productChanged(known).changes.single()

        assertEquals(InventoryEntityType.PRODUCT, change.type)
        assertEquals(4L, change.baseRevision)
        assertFalse(change.deleted)
        assertEquals(JsonPrimitive("Pears"), change.fields["name"])
    }

    @Test
    fun edit_keepsTheBaseRevisionOfTheRowItDescribes() {
        val product = product(revision = 1)

        val change = LocalInventoryOperations.itemsChanged(listOf(product), listOf(item(product, revision = 1, quantity = 2f)))!!
            .changes.single()

        assertEquals(1L, change.baseRevision)
        assertEquals(JsonPrimitive(2.0), change.fields["quantity"])
    }

    @Test
    fun moreThanOneHundredChanges_isNotRepresentableAsOneIntent() {
        val product = product(revision = 1)

        assertEquals(100, LocalInventoryOperations.itemsChanged(listOf(product), List(100) { item(product) })!!.changes.size)
        assertNull(LocalInventoryOperations.itemsChanged(listOf(product), List(101) { item(product) }))
        assertEquals(100, LocalInventoryOperations.itemsDeleted(List(100) { item(product) })!!.changes.size)
        assertNull(LocalInventoryOperations.itemsDeleted(List(101) { item(product) }))
    }

    @Test
    fun anActionThatChangedNothing_isNotAnIntent() {
        val known = product(revision = 1)

        assertNull(LocalInventoryOperations.itemsChanged(listOf(known), emptyList()))
        assertNull(LocalInventoryOperations.itemsDeleted(emptyList()))
    }

    @Test
    fun aRepeatedRow_isOneEntityWithOneChange() {
        val product = product(revision = 1)
        val item = item(product, entityId = "i1")

        val changes = LocalInventoryOperations.itemsChanged(listOf(product), listOf(item, item))!!.changes

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

        assertNull(LocalInventoryOperations.itemsChanged(listOf(product), listOf(orphan)))
    }

    @Test
    fun everyIntent_getsItsOwnOperationId() {
        val product = product(revision = 1)
        val item = item(product)

        val first = LocalInventoryOperations.itemsChanged(listOf(product), listOf(item))!!
        val second = LocalInventoryOperations.itemsChanged(listOf(product), listOf(item))!!

        assertNotEquals(first.id, second.id)
    }
}
