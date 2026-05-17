package com.github.pcha.foodsense.app.data.barcode

import javax.inject.Inject

class BarcodeRepositoryImpl @Inject constructor(
    private val localRegistry: LocalBarcodeRegistry,
    private val openFoodFacts: OpenFoodFactsBarcodeRepository,
) : BarcodeRepository {

    override suspend fun lookup(barcode: String): BarcodeResult? {
        val cached = localRegistry.lookup(barcode)
        if (cached != null) return BarcodeResult(cached, fromCache = true)
        val remote = openFoodFacts.lookup(barcode)
        if (remote != null) return BarcodeResult(remote, fromCache = false)
        return null
    }

    override suspend fun save(barcode: String, product: BarcodeProduct) =
        localRegistry.save(barcode, product)

    override suspend fun delete(barcode: String) =
        localRegistry.delete(barcode)
}
