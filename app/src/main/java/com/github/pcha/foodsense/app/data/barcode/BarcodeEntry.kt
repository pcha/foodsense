package com.github.pcha.foodsense.app.data.barcode

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "barcode_registry")
data class BarcodeEntry(
    @PrimaryKey val barcode: String,
    val name: String,
    val quantity: String?,
)

@Dao
interface BarcodeRegistryDao {
    @Query("SELECT * FROM barcode_registry WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): BarcodeEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BarcodeEntry)

    @Query("DELETE FROM barcode_registry WHERE barcode = :barcode")
    suspend fun delete(barcode: String)
}
