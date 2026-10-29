package com.medremind.app.data

/** The four customary times of day on a Bangladeshi prescription, in pattern order. */
enum class SlotKind(val label: String) {
    MORNING("সকাল"),
    NOON("দুপুর"),
    EVENING("সন্ধ্যা"),
    NIGHT("রাত"),
    ;

    /** Whether a slot set for [hour] belongs to this time of day. */
    fun covers(hour: Int): Boolean = when (this) {
        MORNING -> hour in 4..10
        NOON -> hour in 11..15
        EVENING -> hour in 16..19
        NIGHT -> hour >= 20 || hour < 4
    }
}

/** One reviewed medicine from a prescription scan, ready to add to the schedule. */
data class ImportEntry(
    val name: String,
    val genericName: String?,
    val form: String?,
    val instructions: String?,
    val foodRule: FoodRule,
    /** Units per time of day. Empty means "as needed": the medicine is listed but never rings. */
    val doses: Map<SlotKind, Double>,
    val frequency: Frequency,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
)

/** Dose amounts as people say them: ½, ১, ১½, ২. */
object Quantities {

    private val DIGITS = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')

    fun bengali(quantity: Double): String {
        val whole = quantity.toLong()
        val fraction = quantity - whole
        val half = fraction in 0.4..0.6
        val wholeText = if (whole == 0L && half) "" else whole.toString().map { DIGITS[it - '0'] }.joinToString("")
        return when {
            half -> "$wholeText½"
            fraction < 0.01 -> wholeText
            else -> quantity.toString().map { if (it in '0'..'9') DIGITS[it - '0'] else it }.joinToString("")
        }
    }

    /** `Janmet 50/500` for one unit, `Janmet 50/500 (½টি)` otherwise, as stored in history. */
    fun describe(name: String, quantity: Double): String =
        if (quantity == 1.0) name else "$name (${bengali(quantity)}টি)"
}
