package com.github.pcha.foodsense.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE product ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE product ADD COLUMN expirationDate INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE product_new (
                uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                quantity REAL NOT NULL DEFAULT 1,
                unit TEXT,
                expirationDate INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("INSERT INTO product_new SELECT uid, name, CAST(quantity AS REAL), NULL, expirationDate FROM product")
        db.execSQL("DROP TABLE product")
        db.execSQL("ALTER TABLE product_new RENAME TO product")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE product_new (
                uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("INSERT INTO product_new (name) SELECT DISTINCT name FROM product")
        db.execSQL("""
            CREATE TABLE item (
                uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                productId INTEGER NOT NULL,
                quantity REAL NOT NULL,
                unit TEXT,
                expirationDate INTEGER,
                addedAt INTEGER NOT NULL,
                FOREIGN KEY (productId) REFERENCES product(uid) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_item_productId ON item (productId)")
        db.execSQL("""
            INSERT INTO item (productId, quantity, unit, expirationDate, addedAt)
            SELECT pn.uid, p.quantity, p.unit, p.expirationDate, 0
            FROM product p
            JOIN product_new pn ON p.name = pn.name
        """.trimIndent())
        db.execSQL("DROP TABLE product")
        db.execSQL("ALTER TABLE product_new RENAME TO product")
    }
}

@Database(entities = [ProductEntity::class, ItemEntity::class], version = 4)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun itemDao(): ItemDao
}
