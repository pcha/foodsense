package dev.pcha.foodsense.app.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.pcha.foodsense.app.data.barcode.BarcodeEntry
import dev.pcha.foodsense.app.data.barcode.BarcodeRegistryDao

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

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE barcode_registry (
                barcode TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                quantity TEXT
            )
        """.trimIndent())
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE product ADD COLUMN serverId TEXT")
        db.execSQL("ALTER TABLE item ADD COLUMN serverId TEXT")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE product ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_outbox (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                operationId TEXT NOT NULL,
                accountId TEXT NOT NULL,
                payloadVersion INTEGER NOT NULL,
                payload TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                status TEXT NOT NULL,
                lastErrorCode TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_outbox_operationId ON sync_outbox (operationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_outbox_accountId ON sync_outbox (accountId)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Added with a temporary default so the ALTER works on existing rows; Room ignores a
        // database-side default when the entity declares none. Every row is then given its own
        // identity, so the default is never actually read.
        db.execSQL("ALTER TABLE product ADD COLUMN entityId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE product ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE item ADD COLUMN entityId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE item ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE product SET entityId = lower(hex(randomblob(16)))")
        db.execSQL("UPDATE item SET entityId = lower(hex(randomblob(16)))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_entityId ON product (entityId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_item_entityId ON item (entityId)")
    }
}

@Database(entities = [ProductEntity::class, ItemEntity::class, BarcodeEntry::class, SyncOutboxEntry::class], version = 9)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun itemDao(): ItemDao
    abstract fun barcodeRegistryDao(): BarcodeRegistryDao
    abstract fun syncOutboxDao(): SyncOutboxDao
}
