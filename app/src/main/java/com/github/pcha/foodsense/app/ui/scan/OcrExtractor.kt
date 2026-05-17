package com.github.pcha.foodsense.app.ui.scan

data class ProductOcrResult(
    val productName: String?,
    val quantity: String?,
    val expirationDate: String?,
    val rawText: String,
)

private val BARCODE_NUMBER_REGEX = Regex("""\b(\d{13}|\d{12}|\d{8})\b""")
private val QUANTITY_REGEX = Regex("""(?i)\b(\d+([.,]\d+)?)\s?(ML|L|G|KG|GR|CC|CL)\b""")
private val DATE_REGEX = Regex("""\b(\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4}|\d{1,2}[/\-]\d{2,4})\b""")
private val DATE_NAMED_MONTH_REGEX = Regex("""\b(\d{1,2}\s+[A-Za-z]{3,9}\.?\s+\d{2,4}|[A-Za-z]{3,9}\.?\s+\d{4})\b""")

private val EXPIRY_KEYWORDS = setOf(
    "VENCE", "VENCIMIENTO", "CAD", "CADUCIDAD", "EXP", "CONSUMIR", "BEFORE", "BEST",
)

private val NAME_EXCLUDE_KEYWORDS = setOf(
    "INGREDIENTES", "LOTE", "PESO", "NETO", "NUTRICIONAL", "KCAL", "GRASA", "SAL",
    "CONSERVAR", "CONTENIDO", "TEMPERATURA", "REFRIGERAR", "CONGELAR", "FABRICADO",
)

fun extractBarcodeNumber(lines: List<String>): String? =
    lines.firstNotNullOfOrNull { line ->
        val cleaned = line.replace(Regex("""['"`´''"]"""), "")
        BARCODE_NUMBER_REGEX.find(cleaned)?.groupValues?.get(1)
    }

fun extractProductInfo(lines: List<String>): ProductOcrResult {
    val rawText = lines.joinToString("\n")

    val expirationDate = extractExpirationDate(lines)
    val quantity = extractQuantity(lines)
    val productName = extractProductName(lines, expirationDate)

    return ProductOcrResult(
        productName = productName,
        quantity = quantity,
        expirationDate = expirationDate,
        rawText = rawText,
    )
}

private fun extractExpirationDate(lines: List<String>): String? {
    val upperLines = lines.map { it.uppercase() }

    val expiryLineIndices = upperLines.indices.filter { i ->
        EXPIRY_KEYWORDS.any { kw -> upperLines[i].contains(kw) }
    }

    if (expiryLineIndices.isNotEmpty()) {
        for (idx in expiryLineIndices) {
            for (j in maxOf(0, idx - 1)..minOf(lines.lastIndex, idx + 1)) {
                val match = DATE_REGEX.find(lines[j]) ?: DATE_NAMED_MONTH_REGEX.find(lines[j])
                if (match != null) return match.value
            }
        }
    }

    return lines.firstNotNullOfOrNull { DATE_REGEX.find(it)?.value ?: DATE_NAMED_MONTH_REGEX.find(it)?.value }
}

internal fun extractDateOnly(lines: List<String>): String? = extractExpirationDate(lines)

private fun extractQuantity(lines: List<String>): String? {
    for (line in lines) {
        val match = QUANTITY_REGEX.find(line) ?: continue
        val number = match.groupValues[1].replace(',', '.')
        val unit = match.groupValues[3].uppercase()
        return "$number $unit"
    }
    return null
}

private fun extractProductName(lines: List<String>, dateStr: String?): String? {
    return lines.take(8).firstOrNull { line ->
        val upper = line.uppercase()
        line.length in 3..50
            && NAME_EXCLUDE_KEYWORDS.none { kw -> upper.contains(kw) }
            && (dateStr == null || !line.contains(dateStr))
            && !QUANTITY_REGEX.containsMatchIn(line)
            && !DATE_REGEX.containsMatchIn(line)
            && line.any { it.isLetter() }
    }?.trim()
}
