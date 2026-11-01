package com.blake.app.ui.theme

import java.text.NumberFormat
import java.util.Locale

/** Routes every number shown on screen through one place: whole-number kcal, "92 g",
 * "₹1,240" with en-IN grouping (lakhs/crore style, not western thousands). */
object Format {
    private val inrGrouping = NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
        maximumFractionDigits = 0
    }

    fun kcal(value: Double): String = "${value.toInt()} kcal"
    fun grams(value: Double): String = "${value.toInt()} g"
    fun currency(value: Double): String = "₹${inrGrouping.format(value)}"
    fun weightKg(value: Double): String = "${value.toInt()} kg"
    fun grams(value: Int): String = "$value g"
}
