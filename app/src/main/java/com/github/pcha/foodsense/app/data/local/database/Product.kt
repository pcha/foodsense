package com.github.pcha.foodsense.app.data.local.database

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

@Entity(tableName = "product")
data class ProductEntity(
    val name: String,
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
    indices = [Index("productId")],
)
@TypeConverters(Converters::class)
data class ItemEntity(
    val productId: Int,
    val quantity: Float,
    val unit: ProductUnit?,
    val expirationDate: LocalDate?,
    val addedAt: LocalDate,
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
}

@Dao
interface ItemDao {
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
}
