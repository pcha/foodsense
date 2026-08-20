package dev.pcha.foodsense.app.data.local.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Exercises the DAOs against real SQLite instead of the hand-written fakes used elsewhere.
 *
 * The fakes can't model everything Room does — most importantly `deleteProduct` cascading into
 * `item` — so behaviour the sync code depends on would otherwise never be checked against the
 * real database.
 */
@RunWith(RobolectricTestRunner::class)
class ProductDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var productDao: ProductDao
    private lateinit var itemDao: ItemDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        productDao = db.productDao()
        itemDao = db.itemDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertProduct(name: String, serverId: String? = null, updatedAt: Long = 0L) =
        productDao.insertProduct(ProductEntity(name, serverId, updatedAt)).toInt()

    private suspend fun insertItem(
        productId: Int,
        quantity: Float = 1f,
        expirationDate: LocalDate? = null,
        serverId: String? = null,
    ) = itemDao.insertItem(
        ItemEntity(productId, quantity, null, expirationDate, LocalDate.now(), serverId)
    )

    @Test
    fun deleteProduct_productWithItems_cascadesToItems() = runTest {
        val uid = insertProduct("Milk")
        insertItem(uid)
        insertItem(uid)

        productDao.deleteProduct(uid)

        assertEquals(emptyList<ItemEntity>(), itemDao.getItemsByProduct(uid))
    }

    @Test
    fun deleteSyncedProducts_keepsLocalOnlyRows() = runTest {
        insertProduct("Synced", serverId = "doc-a")
        insertProduct("LocalOnly")

        productDao.deleteSyncedProducts()

        assertEquals(listOf("LocalOnly"), productDao.getAllProductNames().first())
    }

    @Test
    fun deleteProductsWithNoItems_removesOnlyEmptyProducts() = runTest {
        val withItems = insertProduct("Milk")
        insertItem(withItems)
        insertProduct("Empty")

        productDao.deleteProductsWithNoItems()

        assertEquals(listOf("Milk"), productDao.getAllProductNames().first())
    }

    @Test
    fun getProductsWithItems_groupsItemsUnderTheirProduct() = runTest {
        val milk = insertProduct("Milk")
        insertItem(milk, quantity = 1f)
        insertItem(milk, quantity = 2f)
        val eggs = insertProduct("Eggs")
        insertItem(eggs, quantity = 12f)

        val rows = productDao.getProductsWithItems().first().associateBy { it.product.name }

        assertEquals(listOf(1f, 2f), rows.getValue("Milk").items.map { it.quantity }.sorted())
        assertEquals(listOf(12f), rows.getValue("Eggs").items.map { it.quantity })
    }

    @Test
    fun getProductsWithoutServerId_returnsOnlyLocalOnlyRows() = runTest {
        insertProduct("Synced", serverId = "doc-a")
        insertProduct("LocalOnly")

        assertEquals(listOf("LocalOnly"), productDao.getProductsWithoutServerId().map { it.name })
    }

    @Test
    fun getProductNamesExpiringOn_returnsOnlyThatDate() = runTest {
        val target = LocalDate.now().plusDays(3)
        val milk = insertProduct("Milk")
        insertItem(milk, expirationDate = target)
        val eggs = insertProduct("Eggs")
        insertItem(eggs, expirationDate = target.plusDays(1))

        val names = itemDao.getProductNamesExpiringOn(target.toEpochDay())

        assertEquals(listOf("Milk"), names)
    }

    @Test
    fun findProductByName_differentCase_isNotFound() = runTest {
        // Documenta el comportamiento actual: SQLite compara con `=`, que distingue mayúsculas.
        // Como el nombre es la clave de identidad entre dispositivos, "leche" y "Leche" no se
        // mergean. Normalizar el nombre está pendiente y cambiaría este test.
        insertProduct("Leche")

        assertNull(productDao.findProductByName("leche"))
    }
}
