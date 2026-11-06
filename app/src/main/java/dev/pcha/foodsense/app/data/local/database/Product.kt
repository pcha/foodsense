package dev.pcha.foodsense.app.data.local.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

enum class ProductUnit { L, ML, G, KG }

/**
 * Stable V2 entity identity. Generated once per row and never derived from [ProductEntity.serverId] or
 * [ItemEntity.serverId]: the legacy sync path can reassign those, and legacy item ids are inner array
 * ids, not document ids. Random UUIDs cannot collide across devices.
 */
fun newEntityId(): String = java.util.UUID.randomUUID().toString()

class Converters {
    @TypeConverter
    fun fromEpochDay(value: Long?): LocalDate? = value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun toEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun fromProductUnit(unit: ProductUnit?): String? = unit?.name

    @TypeConverter
    fun toProductUnit(value: String?): ProductUnit? = value?.let { ProductUnit.valueOf(it) }
}

@Entity(tableName = "product", indices = [Index(value = ["entityId"], unique = true)])
data class ProductEntity(
    val name: String,
    val serverId: String? = null,
    val updatedAt: Long = 0L,
    /** V2 document id. Immutable once created. */
    val entityId: String = newEntityId(),
    /** Last revision this device knows for the entity; 0 means "not created on the V2 server yet". */
    val revision: Long = 0L,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}

@Entity(
    tableName = "item",
    foreignKeys = [ForeignKey(
        entity = ProductEntity::class,
        parentColumns = ["uid"],
        childColumns = ["productId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("productId"), Index(value = ["entityId"], unique = true)],
)
@TypeConverters(Converters::class)
data class ItemEntity(
    val productId: Int,
    val quantity: Float,
    val unit: ProductUnit?,
    val expirationDate: LocalDate?,
    val addedAt: LocalDate,
    val serverId: String? = null,
    /** V2 document id. Immutable once created. */
    val entityId: String = newEntityId(),
    /** Last revision this device knows for the entity; 0 means "not created on the V2 server yet". */
    val revision: Long = 0L,
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}

data class ProductWithItems(
    @Embedded val product: ProductEntity,
    @Relation(parentColumn = "uid", entityColumn = "productId")
    val items: List<ItemEntity>,
)

@Dao
interface ProductDao {
    @Transaction
    @Query("SELECT * FROM product ORDER BY name ASC")
    fun getProductsWithItems(): Flow<List<ProductWithItems>>

    @Insert
    suspend fun insertProduct(product: ProductEntity): Long

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Query("SELECT * FROM product WHERE name = :name LIMIT 1")
    suspend fun findProductByName(name: String): ProductEntity?

    @Query("DELETE FROM product WHERE uid NOT IN (SELECT DISTINCT productId FROM item)")
    suspend fun deleteProductsWithNoItems()

    @Query("DELETE FROM product WHERE uid = :uid")
    suspend fun deleteProduct(uid: Int)

    @Query("SELECT name FROM product ORDER BY name ASC")
    fun getAllProductNames(): Flow<List<String>>

    @Query("SELECT * FROM product WHERE serverId IS NULL")
    suspend fun getProductsWithoutServerId(): List<ProductEntity>

    @Query("UPDATE product SET serverId = :serverId WHERE uid = :uid")
    suspend fun updateServerId(uid: Int, serverId: String)

    @Query("UPDATE product SET updatedAt = :updatedAt WHERE uid = :uid")
    suspend fun touchUpdatedAt(uid: Int, updatedAt: Long)

    @Query("SELECT * FROM product WHERE serverId = :serverId LIMIT 1")
    suspend fun findProductByServerId(serverId: String): ProductEntity?

    @Query("SELECT * FROM product WHERE entityId = :entityId LIMIT 1")
    suspend fun findProductByEntityId(entityId: String): ProductEntity?

    /** Monotonic: a revision the device already knows is never lowered by a newer acknowledgement. */
    @Query("UPDATE product SET revision = :revision WHERE uid = :uid AND revision < :revision")
    suspend fun advanceRevision(uid: Int, revision: Long): Int

    /**
     * Undoes a revision that was only reserved by an intent that will never be delivered, so the row
     * goes back to meaning "the revision the server knows". Only ever moves backwards.
     */
    @Query("UPDATE product SET revision = :revision WHERE uid = :uid AND revision > :revision")
    suspend fun resetRevision(uid: Int, revision: Long): Int

    @Query("SELECT * FROM product WHERE uid = :uid LIMIT 1")
    suspend fun findProductById(uid: Int): ProductEntity?

    @Query("DELETE FROM product")
    suspend fun deleteAllProducts()

    @Query("DELETE FROM product WHERE serverId IS NOT NULL")
    suspend fun deleteSyncedProducts()
}

@Dao
interface ItemDao {
    /**
     * Replaces every item of a product. Identity is keyed by [ItemEntity.serverId], so an item that
     * still exists on the remote keeps its [ItemEntity.entityId] and its known revision instead of
     * getting a new one on every reconciliation. Only genuinely new items receive a new identity.
     *
     * Blank or repeated remote ids must not hand the same identity to two rows: `entityId` has a unique
     * index, so the first row wins and any repeat falls back to a fresh identity.
     */
    @Transaction
    suspend fun replaceItems(productId: Int, items: List<ItemEntity>) {
        val existing = getItemsByProduct(productId)
        val previousByServerId = existing
            .mapNotNull { item -> item.serverId?.takeIf { it.isNotBlank() }?.let { it to item } }
            .toMap()
        existing.forEach { deleteItem(it.uid) }
        val claimed = mutableSetOf<String>()
        items.forEach { item ->
            val previous = item.serverId?.takeIf { it.isNotBlank() }?.let(previousByServerId::get)
            insertItem(
                if (previous == null || !claimed.add(previous.entityId)) item
                else item.copy(entityId = previous.entityId, revision = previous.revision)
            )
        }
    }

    @Insert
    suspend fun insertItem(item: ItemEntity)

    @Update
    suspend fun updateItem(item: ItemEntity)

    @Query("SELECT * FROM item WHERE uid = :uid")
    suspend fun getItem(uid: Int): ItemEntity?

    @Query("DELETE FROM item WHERE uid = :uid")
    suspend fun deleteItem(uid: Int)

    @Query("SELECT DISTINCT p.name FROM item i INNER JOIN product p ON i.productId = p.uid WHERE i.expirationDate = :epochDay")
    suspend fun getProductNamesExpiringOn(epochDay: Long): List<String>

    @Query("UPDATE item SET serverId = :serverId WHERE uid = :uid")
    suspend fun updateServerId(uid: Int, serverId: String)

    @Query("SELECT * FROM item WHERE productId = :productId")
    suspend fun getItemsByProduct(productId: Int): List<ItemEntity>

    @Query("SELECT * FROM item WHERE entityId = :entityId LIMIT 1")
    suspend fun findItemByEntityId(entityId: String): ItemEntity?

    /** Monotonic: a revision the device already knows is never lowered by a newer acknowledgement. */
    @Query("UPDATE item SET revision = :revision WHERE uid = :uid AND revision < :revision")
    suspend fun advanceRevision(uid: Int, revision: Long): Int

    /**
     * Undoes a revision that was only reserved by an intent that will never be delivered, so the row
     * goes back to meaning "the revision the server knows". Only ever moves backwards.
     */
    @Query("UPDATE item SET revision = :revision WHERE uid = :uid AND revision > :revision")
    suspend fun resetRevision(uid: Int, revision: Long): Int
}
