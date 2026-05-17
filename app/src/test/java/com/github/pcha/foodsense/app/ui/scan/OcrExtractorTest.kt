package com.github.pcha.foodsense.app.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrExtractorTest {

    @Test
    fun extractProductInfo_dateNearVence_extractsAsExpirationDate() {
        val lines = listOf("Leche Entera", "250 ML", "VENCE:", "15/03/2026")
        val result = extractProductInfo(lines)
        assertEquals("15/03/2026", result.expirationDate)
    }

    @Test
    fun extractProductInfo_dateWithoutKeyword_extractsAsFallback() {
        val lines = listOf("Yogur Natural", "500 G", "10/06/26")
        val result = extractProductInfo(lines)
        assertEquals("10/06/26", result.expirationDate)
    }

    @Test
    fun extractProductInfo_noDate_expirationDateIsNull() {
        val lines = listOf("Arroz Largo Fino", "1 KG")
        val result = extractProductInfo(lines)
        assertNull(result.expirationDate)
    }

    @Test
    fun extractProductInfo_quantity250ml_parsedCorrectly() {
        val lines = listOf("Jugo de Naranja", "250 ML")
        val result = extractProductInfo(lines)
        assertEquals("250 ML", result.quantity)
    }

    @Test
    fun extractProductInfo_quantityWithComma_normalizedToDot() {
        val lines = listOf("Aceite", "0,75 L")
        val result = extractProductInfo(lines)
        assertEquals("0.75 L", result.quantity)
    }

    @Test
    fun extractProductInfo_noQuantity_quantityIsNull() {
        val lines = listOf("Manzanas Fuji", "VENCE 20/12/26")
        val result = extractProductInfo(lines)
        assertNull(result.quantity)
    }

    @Test
    fun extractProductInfo_lineWithIngredientes_excludedFromName() {
        val lines = listOf("INGREDIENTES: agua, leche", "Manteca Extra", "200 G")
        val result = extractProductInfo(lines)
        assertEquals("Manteca Extra", result.productName)
    }

    @Test
    fun extractProductInfo_firstValidLine_chosenAsName() {
        val lines = listOf("Mermelada de Frutilla", "500 G", "VENCE 01/01/2027")
        val result = extractProductInfo(lines)
        assertEquals("Mermelada de Frutilla", result.productName)
    }

    @Test
    fun extractProductInfo_noValidNameLine_productNameIsNull() {
        val lines = listOf("12/03/2026", "500 ML", "INGREDIENTES: azucar, agua")
        val result = extractProductInfo(lines)
        assertNull(result.productName)
    }

    @Test
    fun extractProductInfo_rawTextContainsAllLines() {
        val lines = listOf("Harina 000", "1 KG", "VENCE 05/05/26")
        val result = extractProductInfo(lines)
        assertEquals(lines.joinToString("\n"), result.rawText)
    }

    @Test
    fun extractBarcodeNumber_ean13_detected() {
        val lines = listOf("Leche Entera", "7790040012345", "500 ML")
        assertEquals("7790040012345", extractBarcodeNumber(lines))
    }

    @Test
    fun extractBarcodeNumber_ean8_detected() {
        val lines = listOf("Producto", "12345678")
        assertEquals("12345678", extractBarcodeNumber(lines))
    }

    @Test
    fun extractBarcodeNumber_quotesIntercaladas_ignoradas() {
        assertEquals("7790040012345", extractBarcodeNumber(listOf("779004`001`2345")))
        assertEquals("7790040012345", extractBarcodeNumber(listOf("779004'0012345")))
        assertEquals("7790040012345", extractBarcodeNumber(listOf("7790\"040012345")))
    }

    @Test
    fun extractBarcodeNumber_noBarcode_returnsNull() {
        val lines = listOf("Arroz", "1 KG", "VENCE 10/01/26")
        assertNull(extractBarcodeNumber(lines))
    }

    @Test
    fun extractBarcodeNumber_preferLongerFirst_ean13OverEan8() {
        val lines = listOf("7790040012345 12345678")
        assertEquals("7790040012345", extractBarcodeNumber(lines))
    }

    @Test
    fun extractProductInfo_expiryKeywordOnSameLine_extractsDate() {
        val lines = listOf("Queso Cremoso", "VENCE 30/06/2026", "200 G")
        val result = extractProductInfo(lines)
        assertEquals("30/06/2026", result.expirationDate)
    }
}
