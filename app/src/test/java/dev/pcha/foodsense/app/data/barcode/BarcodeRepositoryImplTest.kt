package dev.pcha.foodsense.app.data.barcode

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeRepositoryImplTest {

    @Test
    fun lookup_cacheHit_returnsFromCacheWithoutRemote() = runTest {
        val cache = FakeBarcodeCache().apply { save("123", BarcodeProduct("Milk", "1 L")) }
        val remote = FakeRemoteBarcodeSource()
        val repo = BarcodeRepositoryImpl(cache, remote)

        val result = repo.lookup("123")

        assertEquals("Milk", result?.product?.name)
        assertTrue(result!!.fromCache)
        assertEquals(0, remote.lookupCount)
    }

    @Test
    fun lookup_cacheMiss_fallsBackToRemote() = runTest {
        val cache = FakeBarcodeCache()
        val remote = FakeRemoteBarcodeSource().apply { products["123"] = BarcodeProduct("Eggs", null) }
        val repo = BarcodeRepositoryImpl(cache, remote)

        val result = repo.lookup("123")

        assertEquals("Eggs", result?.product?.name)
        assertEquals(false, result!!.fromCache)
    }

    @Test
    fun lookup_notFoundAnywhere_returnsNull() = runTest {
        val repo = BarcodeRepositoryImpl(FakeBarcodeCache(), FakeRemoteBarcodeSource())

        assertNull(repo.lookup("nope"))
    }

    @Test
    fun save_thenLookup_returnsSavedProduct() = runTest {
        val cache = FakeBarcodeCache()
        val repo = BarcodeRepositoryImpl(cache, FakeRemoteBarcodeSource())

        repo.save("123", BarcodeProduct("Butter", "200 G"))

        assertEquals("Butter", repo.lookup("123")?.product?.name)
    }

    @Test
    fun delete_removesFromCache() = runTest {
        val cache = FakeBarcodeCache().apply { save("123", BarcodeProduct("Milk", null)) }
        val repo = BarcodeRepositoryImpl(cache, FakeRemoteBarcodeSource())

        repo.delete("123")

        assertNull(repo.lookup("123"))
    }
}

private class FakeBarcodeCache : BarcodeCache {
    private val entries = mutableMapOf<String, BarcodeProduct>()
    override suspend fun lookup(barcode: String): BarcodeProduct? = entries[barcode]
    override suspend fun save(barcode: String, product: BarcodeProduct) { entries[barcode] = product }
    override suspend fun delete(barcode: String) { entries.remove(barcode) }
}

private class FakeRemoteBarcodeSource : RemoteBarcodeSource {
    val products = mutableMapOf<String, BarcodeProduct>()
    var lookupCount = 0
    override suspend fun lookup(barcode: String): BarcodeProduct? {
        lookupCount++
        return products[barcode]
    }
}
