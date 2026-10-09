package dev.pcha.foodsense.app.data.barcode

import javax.inject.Inject

/** Local (Room-backed) cache of barcode → product mappings. */
interface BarcodeCache {
    suspend fun lookup(barcode: String): BarcodeProduct?
    suspend fun save(barcode: String, product: BarcodeProduct)
    suspend fun delete(barcode: String)
}

class LocalBarcodeRegistry @Inject constructor(
    private val dao: BarcodeRegistryDao,
) : BarcodeCache {
    override suspend fun lookup(barcode: String): BarcodeProduct? =
        dao.findByBarcode(barcode)?.let { BarcodeProduct(it.name, it.quantity) }

    override suspend fun save(barcode: String, product: BarcodeProduct) =
        dao.insert(BarcodeEntry(barcode, product.name, product.quantity))

    override suspend fun delete(barcode: String) = dao.delete(barcode)
}
