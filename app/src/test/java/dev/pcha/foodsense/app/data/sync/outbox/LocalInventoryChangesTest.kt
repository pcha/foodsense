package dev.pcha.foodsense.app.data.sync.outbox

import dev.pcha.foodsense.app.data.local.database.ItemEntity
import dev.pcha.foodsense.app.data.local.database.ProductEntity
import dev.pcha.foodsense.app.data.local.database.ProductUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Pins the Room -> protocol field contract without needing a database. */
class LocalInventoryChangesTest {
    private fun product(name: String = "Milk", revision: Long = 0L) =
        ProductEntity(name = name, revision = revision)

    private fun item(
        quantity: Float = 2f,
        unit: ProductUnit? = ProductUnit.KG,
        expirationDate: LocalDate? = LocalDate.ofEpochDay(20_744),
        addedAt: LocalDate = LocalDate.ofEpochDay(20_700),
        revision: Long = 0L,
    ) = ItemEntity(1, quantity, unit, expirationDate, addedAt, revision = revision)

    private fun planOf(change: InventoryChange) =
        InventoryOperationProtocol.plan(InventoryOperation("op", listOf(change)), emptyMap())

    @Test
    fun product_passesProtocolFieldValidation() {
        val written = planOf(LocalInventoryChanges.product(product())).writes.single()

        assertEquals(JsonPrimitive("Milk"), written.fields["name"])
        assertEquals(1L, written.revision)
    }

    @Test
    fun item_passesProtocolFieldValidationAndKeepsEveryField() {
        val entity = item()
        val written = planOf(LocalInventoryChanges.item(entity, productEntityId = "p-1")).writes.single()

        assertEquals(entity.entityId, written.key.id)
        assertEquals(JsonPrimitive("p-1"), written.fields["productId"])
        assertEquals(JsonPrimitive(2.0), written.fields["quantity"])
        assertEquals(JsonPrimitive("KG"), written.fields["unit"])
        assertEquals(JsonPrimitive(20_744L), written.fields["expirationDate"])
        assertEquals(JsonPrimitive(20_700L), written.fields["addedAt"])
    }

    @Test
    fun item_withoutUnitOrExpiration_sendsExplicitNulls() {
        val change = LocalInventoryChanges.item(item(unit = null, expirationDate = null), productEntityId = "p-1")

        assertEquals(JsonNull, change.fields["unit"])
        assertEquals(JsonNull, change.fields["expirationDate"])
        assertTrue(runCatching { planOf(change) }.isSuccess)
    }

    @Test
    fun deletions_carryNoFieldsButKeepIdentityAndRevision() {
        val productDeletion = LocalInventoryChanges.productDeletion(product(revision = 3))
        val itemDeletion = LocalInventoryChanges.itemDeletion(item(revision = 5))

        assertTrue(productDeletion.deleted)
        assertTrue(productDeletion.fields.isEmpty())
        assertEquals(3L, productDeletion.baseRevision)
        assertTrue(itemDeletion.deleted)
        assertTrue(itemDeletion.fields.isEmpty())
        assertEquals(5L, itemDeletion.baseRevision)
    }

    @Test
    fun baseRevision_comesFromTheRowsKnownRevision_notTheClock() {
        assertEquals(7L, LocalInventoryChanges.product(product(revision = 7)).baseRevision)
        assertEquals(9L, LocalInventoryChanges.item(item(revision = 9), "p-1").baseRevision)
    }

    @Test
    fun entities_defaultToDistinctNonBlankIdentity() {
        val first = ProductEntity("Milk")
        val second = ProductEntity("Milk")

        assertTrue(first.entityId.isNotBlank())
        assertNotEquals(first.entityId, second.entityId)
        assertEquals(0L, first.revision)
    }

    @Test
    fun nonPositiveQuantity_isRejectedByTheProtocol() {
        // The editor already refuses to save a non-positive quantity; this documents the shared invariant.
        val change = LocalInventoryChanges.item(item(quantity = 0f), productEntityId = "p-1")

        assertTrue(runCatching { planOf(change) }.isFailure)
    }
}
