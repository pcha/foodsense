package dev.pcha.foodsense.app.data.local.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrate6To7_addsUpdatedAtDefaultingToZero() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL("INSERT INTO product (uid, name, serverId) VALUES (1, 'Milk', NULL)")
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        db.query("SELECT updatedAt FROM product WHERE uid = 1").use { cursor ->
            cursor.moveToFirst()
            val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt"))
            assertEquals(0L, updatedAt)
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrateAll_2To7_succeeds() {
        // El schema v1 nunca se exportó a app/schemas/ (el más viejo es 2.json), así que la cadena
        // sólo se puede validar desde ahí. MIGRATION_1_2 queda sin cobertura.
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(
            TEST_DB, 7, true,
            MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
        )
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
