package com.github.pcha.foodsense.app.data.barcode

import javax.inject.Inject

class LocalBarcodeRegistry @Inject constructor(
    private val dao: BarcodeRegistryDao,
) {
    suspend fun lookup(barcode: String): BarcodeProduct? =
        dao.findByBarcode(barcode)?.let { BarcodeProduct(it.name, it.quantity) }

    suspend fun save(barcode: String, product: BarcodeProduct) =
        dao.insert(BarcodeEntry(barcode, product.name, product.quantity))

    suspend fun delete(barcode: String) = dao.delete(barcode)
}
