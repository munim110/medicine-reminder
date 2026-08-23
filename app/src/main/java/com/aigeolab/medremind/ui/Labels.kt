package com.aigeolab.medremind.ui

import androidx.annotation.StringRes
import com.aigeolab.medremind.R
import com.aigeolab.medremind.data.DoseStatus
import com.aigeolab.medremind.data.FoodRule
import java.time.LocalDate

@StringRes
fun foodRuleLabel(rule: FoodRule): Int = when (rule) {
    FoodRule.EMPTY_STOMACH -> R.string.food_empty_stomach
    FoodRule.BEFORE_FOOD -> R.string.food_before
    FoodRule.AFTER_FOOD -> R.string.food_after
    FoodRule.ANY -> R.string.food_any
}

@StringRes
fun statusLabel(status: DoseStatus?): Int = when (status) {
    DoseStatus.TAKEN -> R.string.status_taken
    DoseStatus.SKIPPED -> R.string.status_skipped
    DoseStatus.MISSED -> R.string.status_missed
    null -> R.string.status_pending
}

/** Bengali numerals and date/time wording, so the UI reads naturally alongside the chart. */
object Bn {

    private val DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    private val MONTHS = arrayOf(
        "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
        "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর",
    )

    fun digits(text: String): String = buildString {
        for (ch in text) append(if (ch in '0'..'9') DIGITS[ch - '0'] else ch)
    }

    fun num(value: Int): String = digits(value.toString())

    /** e.g. `সকাল ৮:০০`, `দুপুর ২:৩০`, `রাত ১০:০০`. */
    fun time(hour: Int, minute: Int): String {
        val period = when (hour) {
            in 0..3 -> "রাত"
            in 4..5 -> "ভোর"
            in 6..11 -> "সকাল"
            in 12..15 -> "দুপুর"
            in 16..17 -> "বিকাল"
            in 18..19 -> "সন্ধ্যা"
            else -> "রাত"
        }
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        return "$period ${num(hour12)}:${digits(minute.toString().padStart(2, '0'))}"
    }

    /** Clock only, without the period word — for lists where the period is already implied. */
    fun timeShort(hour: Int, minute: Int): String {
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        return "${num(hour12)}:${digits(minute.toString().padStart(2, '0'))}"
    }

    fun date(date: LocalDate): String =
        "${num(date.dayOfMonth)} ${MONTHS[date.monthValue - 1]} ${num(date.year)}"

    fun relativeDate(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "আজ"
        today.minusDays(1) -> "গতকাল"
        else -> date(date)
    }
}
