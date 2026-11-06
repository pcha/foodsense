package dev.pcha.foodsense.app.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** Room v9 entity identity: generated once, never reused or replaced by reconciliation. */
@RunWith(RobolectricTestRunner::class)
class EntityIdentityTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertedRows_getDistinctNonBlankIdentityAtRevisionZero() = runTest {
        val productId = db.productDao().insertProduct(ProductEntity("Milk")).toInt()
        db.itemDao().insertItem(ItemEntity(productId, 1f, null, null, LocalDate.now()))
        db.itemDao().insertItem(ItemEntity(productId, 1f, null, null, LocalDate.now()))

        val product = db.productDao().findProductById(productId)!!
        assertTrue(product.entityId.isNotBlank())
        assertEquals(0L, product.revision)
        val items = db.itemDao().getItemsByProduct(productId)
        assertEquals(2, items.map { it.entityId }.distinct().size)
        assertTrue(items.all { it.entityId.isNotBlank() && it.revision == 0L })
    }

    @Test
    fun productCopy_keepsTheSameIdentity() = runTest {
        val productId = db.productDao().insertProduct(ProductEntity("Milk")).toInt()
        val original = db.productDao().findProductById(productId)!!

        db.productDao().updateProduct(original.copy(name = "Oat Milk", updatedAt = 99L).also { it.uid = productId })

        val updated = db.productDao().findProductById(productId)!!
        assertEquals(original.entityId, updated.entityId)
        assertEquals("Oat Milk", updated.name)
    }

    @Test
    fun replaceItems_matchingServerIds_preservesIdentityAndRevision() = runTest {
        val productId = db.productDao().insertProduct(ProductEntity("Milk")).toInt()
        db.itemDao().insertItem(ItemEntity(productId, 1f, null, null, LocalDate.now(), "s-1", revision = 4L))
        db.itemDao().insertItem(ItemEntity(productId, 1f, null, null, LocalDate.now(), "s-2"))
        val before = db.itemDao().getItemsByProduct(productId).associateBy { it.serverId!! }

        db.itemDao().replaceItems(productId, listOf(
            ItemEntity(productId, 9f, null, null, LocalDate.now(), "s-1"),
            ItemEntity(productId, 1f, null, null, LocalDate.now(), "s-3"),
        ))

        val after = db.itemDao().getItemsByProduct(productId).associateBy { it.serverId!! }
        assertEquals(setOf("s-1", "s-3"), after.keys)
        assertEquals(before.getValue("s-1").entityId, after.getValue("s-1").entityId)
        assertEquals(4L, after.getValue("s-1").revision)
        assertEquals(9f, after.getValue("s-1").quantity)
        assertNotEquals(before.getValue("s-2").entityId, after.getValue("s-3").entityId)
    }

    @Test
    fun replaceItems_withoutServerIds_assignsFreshIdentityPerRow() = runTest {
        val productId = db.productDao().insertProduct(ProductEntity("Milk")).toInt()

        db.itemDao().replaceItems(productId, listOf(
            ItemEntity(productId, 1f, null, null, LocalDate.now()),
            ItemEntity(productId, 1f, null, null, LocalDate.now()),
        ))

        assertEquals(2, db.itemDao().getItemsByProduct(productId).map { it.entityId }.distinct().size)
    }

    @Test
    fun replaceItems_repeatedServerId_keepsOneIdentityWithoutColliding() = runTest {
        val productId = db.productDao().insertProduct(ProductEntity("Milk")).toInt()
        db.itemDao().insertItem(ItemEntity(productId, 1f, null, null, LocalDate.now(), "dup", revision = 2L))

        db.itemDao().replaceItems(productId, listOf(
            ItemEntity(productId, 1f, null, null, LocalDate.now(), "dup"),
            ItemEntity(productId, 3f, null, null, LocalDate.now(), "dup"),
        ))

        val after = db.itemDao().getItemsByProduct(productId)
        assertEquals(2, after.size)
        assertEquals(2, after.map { it.entityId }.distinct().size)
        assertEquals(1, after.count { it.revision == 2L })
    }

    @Test
    fun replaceItems_blankServerId_neverReusesAnIdentity() = runTest {
        val productId = db.productDao().insertProduct(ProductEntity("Milk")).toInt()
        db.itemDao().insertItem(ItemEntity(productId, 1f, null, null, LocalDate.now(), ""))

        db.itemDao().replaceItems(productId, listOf(
            ItemEntity(productId, 1f, null, null, LocalDate.now(), ""),
            ItemEntity(productId, 2f, null, null, LocalDate.now(), ""),
        ))

        val after = db.itemDao().getItemsByProduct(productId)
        assertEquals(2, after.size)
        assertEquals(2, after.map { it.entityId }.distinct().size)
        assertTrue(after.all { it.revision == 0L })
    }
}
