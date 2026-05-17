package com.github.pcha.foodsense.app.data.barcode

data class BarcodeProduct(
    val name: String,
    val quantity: String?,
)

data class BarcodeResult(
    val product: BarcodeProduct,
    val fromCache: Boolean,
)

interface BarcodeRepository {
    suspend fun lookup(barcode: String): BarcodeResult?
    suspend fun save(barcode: String, product: BarcodeProduct)
    suspend fun delete(barcode: String)
}
