package com.github.pcha.foodsense.app.data.barcode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

class OpenFoodFactsBarcodeRepository @Inject constructor() {

    suspend fun lookup(barcode: String): BarcodeProduct? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://world.openfoodfacts.org/api/v0/product/$barcode.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("User-Agent", "FoodSense/1.0")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = JSONObject(body)
            if (json.optInt("status") != 1) return@withContext null

            val product = json.optJSONObject("product") ?: return@withContext null
            val name = product.optString("product_name").takeIf { it.isNotBlank() }
                ?: return@withContext null

            BarcodeProduct(
                name = name,
                quantity = product.optString("quantity").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }
}
