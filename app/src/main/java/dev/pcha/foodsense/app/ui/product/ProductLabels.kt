package dev.pcha.foodsense.app.ui.product

import android.content.Context
import androidx.compose.ui.graphics.Color
import dev.pcha.foodsense.app.R
import dev.pcha.foodsense.app.data.local.database.ProductUnit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal val ExpiryColorUrgent = Color(0xFFFF9800)
internal val ExpiryColorWarning = Color(0xFF4CAF50)

internal fun quantityLabel(count: Int, quantity: Float, unit: ProductUnit?, context: Context): String {
    val qtyStr = if (quantity == quantity.toLong().toFloat()) quantity.toLong().toString() else quantity.toString()
    val base = if (unit != null) "$qtyStr ${unit.displayLabel()}"
    else context.getString(R.string.quantity_without_unit, qtyStr)
    return if (count > 1) context.getString(R.string.quantity_multiplier, count, base) else base
}

private fun relativeMagnitude(from: LocalDate, to: LocalDate, context: Context): String {
    val years = ChronoUnit.YEARS.between(from, to)
    val months = ChronoUnit.MONTHS.between(from, to)
    val days = ChronoUnit.DAYS.between(from, to)

    fun qty(n: Long, pluralsId: Int) = context.resources.getQuantityString(pluralsId, n.toInt(), n)

    return when {
        years >= 1L -> {
            val remMonths = ChronoUnit.MONTHS.between(from.plusYears(years), to)
            val yearsStr = qty(years, R.plurals.expiry_years)
            if (remMonths > 0L) context.getString(R.string.expiry_and, yearsStr, qty(remMonths, R.plurals.expiry_months))
            else yearsStr
        }
        months >= 1L -> {
            val remDays = ChronoUnit.DAYS.between(from.plusMonths(months), to)
            val monthsStr = qty(months, R.plurals.expiry_months)
            if (remDays > 0L) context.getString(R.string.expiry_and, monthsStr, qty(remDays, R.plurals.expiry_days))
            else monthsStr
        }
        else -> qty(days, R.plurals.expiry_days)
    }
}

internal fun expiryLabel(daysUntil: Long, date: LocalDate, today: LocalDate, context: Context): String {
    val magnitude = if (daysUntil < 0) relativeMagnitude(date, today, context)
                    else relativeMagnitude(today, date, context)
    return when {
        daysUntil == 0L  -> context.getString(R.string.expiry_today)
        daysUntil == 1L  -> context.getString(R.string.expiry_tomorrow)
        daysUntil == -1L -> context.getString(R.string.expired_yesterday)
        daysUntil < 0    -> context.getString(R.string.expired_ago, magnitude)
        else             -> context.getString(R.string.expiry_in, magnitude)
    }
}

internal fun addedLabel(addedAt: LocalDate, today: LocalDate, context: Context): String {
    return when (ChronoUnit.DAYS.between(addedAt, today)) {
        0L   -> context.getString(R.string.added_today)
        1L   -> context.getString(R.string.added_yesterday)
        else -> context.getString(R.string.added_ago, relativeMagnitude(addedAt, today, context))
    }
}

internal fun ProductUnit.displayLabel(): String = when (this) {
    ProductUnit.L -> "L"
    ProductUnit.ML -> "mL"
    ProductUnit.G -> "g"
    ProductUnit.KG -> "kg"
}
