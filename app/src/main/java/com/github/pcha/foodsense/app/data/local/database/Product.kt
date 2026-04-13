package com.github.pcha.foodsense.app.data.local.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun fromEpochDay(value: Long): LocalDate = LocalDate.ofEpochDay(value)

    @TypeConverter
    fun toEpochDay(date: LocalDate): Long = date.toEpochDay()
}

@Entity
@TypeConverters(Converters::class)
data class Product(
    val name: String,
    val quantity: Int,
    val expirationDate: LocalDate
) {
    @PrimaryKey(autoGenerate = true)
    var uid: Int = 0
}

@Dao
interface ProductDao {
    @Query("SELECT * FROM product ORDER BY expirationDate ASC")
    fun getProducts(): Flow<List<Product>>

    @Insert
    suspend fun insertProduct(item: Product)

    @Update
    suspend fun updateProduct(item: Product)

    @Query("DELETE FROM product WHERE uid = :uid")
    suspend fun deleteProduct(uid: Int)
}
