package com.medremind.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter
import java.time.LocalDate

/** When a medicine must be taken relative to a meal. */
enum class FoodRule {
    EMPTY_STOMACH,
    BEFORE_FOOD,
    AFTER_FOOD,
    ANY,
}

/** How often a scheduled medicine is due in its slot. */
enum class Frequency {
    DAILY,
    WEEKLY,
    MONTHLY,
}

/** Outcome of a scheduled dose. */
enum class DoseStatus {
    TAKEN,
    SKIPPED,
    MISSED,
}

/**
 * One alarm slot in the day (সকাল / দুপুর / সন্ধ্যা / রাত).
 *
 * [hour] and [minute] are nullable so a slot can exist before its time is known — the
 * morning slot ships unset because the source chart gave no clock time for it.
 */
@Entity(tableName = "dose_groups")
data class DoseGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val hour: Int? = null,
    val minute: Int? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    /**
     * When this slot's current schedule started being tracked: creation, and again on any change
     * that can make it due on days it was not (a time, a medicine, a frequency, re-enabling).
     * Doses scheduled before it are never written off as missed — the past is judged by today's
     * schedule, so without this a new medicine would backfill a week of misses.
     */
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
) {
    val isTimeSet: Boolean get() = hour != null && minute != null
}

/**
 * A medicine, shared by every slot it is taken in, so renaming it or tracking its stock happens once.
 * Deleting it removes it from every slot.
 */
@Entity(tableName = "medicines")
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** As written on the prescription, usually brand and strength, e.g. `Janmet 50/500`. */
    val name: String,
    /** Active ingredient, when known from the drug list. Lets the family tell brands apart. */
    val genericName: String? = null,
    /** Tablet, capsule, syrup… as the drug list or prescription gives it. Display only. */
    val form: String? = null,
    /** Free-text directions copied from the prescription. */
    val instructions: String? = null,
    /** Units on hand. Null when refills are not tracked for this medicine. */
    val stock: Double? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
)

/** One medicine in one [DoseGroup]: how much, relative to food, and on which days. */
@Entity(
    tableName = "dose_items",
    foreignKeys = [
        ForeignKey(
            entity = DoseGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Medicine::class,
            parentColumns = ["id"],
            childColumns = ["medicineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId"), Index("medicineId")],
)
data class DoseItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val medicineId: Long,
    /** Units per dose: 0.5 for half a tablet, 2 for two. */
    @ColumnInfo(defaultValue = "1") val quantity: Double = 1.0,
    val foodRule: FoodRule,
    @ColumnInfo(defaultValue = "DAILY") val frequency: Frequency = Frequency.DAILY,
    /** ISO day of week, 1 = Monday … 7 = Sunday. Used when [frequency] is WEEKLY. */
    val dayOfWeek: Int? = null,
    /** 1–31. Used when [frequency] is MONTHLY; a short month uses its last day instead. */
    val dayOfMonth: Int? = null,
    val sortOrder: Int = 0,
) {
    fun isDueOn(date: LocalDate): Boolean = when (frequency) {
        Frequency.DAILY -> true
        Frequency.WEEKLY -> date.dayOfWeek.value == (dayOfWeek ?: return false)
        Frequency.MONTHLY -> date.dayOfMonth == (dayOfMonth ?: return false).coerceIn(1, date.lengthOfMonth())
    }

    /** Average units used per day, for estimating when the stock runs out. */
    val unitsPerDay: Double
        get() = when (frequency) {
            Frequency.DAILY -> quantity
            Frequency.WEEKLY -> quantity / 7
            Frequency.MONTHLY -> quantity * 12 / 365
        }
}

/**
 * Record of what happened to one scheduled dose.
 *
 * Deliberately has no foreign key on [groupId]: history must survive editing or deleting
 * the schedule, so [label] and [medicines] snapshot the state at the time it fired.
 */
@Entity(
    tableName = "dose_logs",
    indices = [
        Index(value = ["groupId", "date"], unique = true),
        Index("date"),
    ],
)
data class DoseLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    /** Local date the dose belonged to, as `yyyy-MM-dd`. */
    val date: String,
    val scheduledAt: Long,
    val actionedAt: Long,
    val status: DoseStatus,
    val label: String,
    @ColumnInfo(defaultValue = "") val medicines: String = "",
    /** Unused since sync keys rows by slot and date (see `CloudSync`); kept to avoid a table rebuild. */
    @ColumnInfo(defaultValue = "") val uuid: String = java.util.UUID.randomUUID().toString(),
    /** False until this row has been uploaded for the family to see. */
    @ColumnInfo(defaultValue = "0") val synced: Boolean = false,
)

/**
 * How an unanswered alarm escalates. Editable from the settings screen.
 *
 * With the defaults, a dose due at 3:00 rings 3:00–3:02, again 3:05–3:07, and so on, until
 * 3:30 when it is written off as missed.
 */
@Entity(tableName = "settings")
data class AlarmSettings(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** How long the alarm rings before falling silent, in seconds. */
    val ringSeconds: Int = 120,
    /** Gap between the start of one ring and the start of the next, in seconds. */
    val retryIntervalSeconds: Int = 300,
    /** Window after the scheduled time before the dose is recorded as missed, in seconds. */
    val giveUpSeconds: Int = 1800,
    /** Snooze length offered on the alarm screen, in minutes. */
    val snoozeMinutes: Int = 10,
) {
    val giveUpMillis: Long get() = giveUpSeconds * 1000L
    val ringMillis: Long get() = ringSeconds * 1000L

    fun isPastGiveUp(scheduledAt: Long, now: Long): Boolean =
        scheduledAt > 0 && now >= scheduledAt + giveUpMillis

    /**
     * When the alarm should next start ringing, or null once the give-up window has closed.
     * The final value returned is the give-up instant itself, which is what marks the dose missed.
     */
    fun nextRetryAt(scheduledAt: Long, now: Long): Long? {
        if (scheduledAt <= 0) return null
        val giveUpAt = scheduledAt + giveUpMillis
        if (now >= giveUpAt) return null
        val interval = (retryIntervalSeconds.coerceAtLeast(60)) * 1000L
        val steps = ((now - scheduledAt).coerceAtLeast(0) / interval) + 1
        val next = scheduledAt + steps * interval
        return if (next >= giveUpAt) giveUpAt else next
    }

    companion object {
        const val SINGLETON_ID = 1
    }
}

/** A [DoseItem] with the medicine it refers to. */
data class SlotMedicine(
    @Embedded val item: DoseItem,
    @Relation(parentColumn = "medicineId", entityColumn = "id")
    val medicine: Medicine,
) {
    val name: String get() = medicine.name
}

/** A dose slot together with its medicines, ordered for display. */
data class GroupWithItems(
    @Embedded val group: DoseGroup,
    @Relation(entity = DoseItem::class, parentColumn = "id", entityColumn = "groupId")
    val items: List<SlotMedicine> = emptyList(),
) {
    val sortedItems: List<SlotMedicine> get() = items.sortedBy { it.item.sortOrder }

    /** What this slot asks for on [date]; weekly and monthly medicines appear only on their day. */
    fun itemsDueOn(date: LocalDate): List<SlotMedicine> = sortedItems.filter { it.item.isDueOn(date) }

    /** A slot rings only on days it has something to take. */
    fun isDueOn(date: LocalDate): Boolean = items.any { it.item.isDueOn(date) }
}

/** A medicine with every slot entry that uses it. */
data class MedicineWithItems(
    @Embedded val medicine: Medicine,
    @Relation(parentColumn = "id", entityColumn = "medicineId")
    val items: List<DoseItem> = emptyList(),
) {
    val unitsPerDay: Double get() = items.sumOf { it.unitsPerDay }

    /** Whole days of stock left at the scheduled rate, or null when not tracked or not scheduled. */
    val daysLeft: Int?
        get() {
            val stock = medicine.stock ?: return null
            val perDay = unitsPerDay
            return if (perDay <= 0.0) null else (stock / perDay).toInt()
        }

    val isLowOnStock: Boolean get() = daysLeft?.let { it <= LOW_STOCK_DAYS } ?: false

    companion object {
        /** Warn this many days ahead: time enough to buy more without a gap. */
        const val LOW_STOCK_DAYS = 5
    }
}

class Converters {
    @TypeConverter
    fun fromFoodRule(value: FoodRule): String = value.name

    @TypeConverter
    fun toFoodRule(value: String): FoodRule =
        runCatching { FoodRule.valueOf(value) }.getOrDefault(FoodRule.ANY)

    @TypeConverter
    fun fromFrequency(value: Frequency): String = value.name

    @TypeConverter
    fun toFrequency(value: String): Frequency =
        runCatching { Frequency.valueOf(value) }.getOrDefault(Frequency.DAILY)

    @TypeConverter
    fun fromDoseStatus(value: DoseStatus): String = value.name

    @TypeConverter
    fun toDoseStatus(value: String): DoseStatus =
        runCatching { DoseStatus.valueOf(value) }.getOrDefault(DoseStatus.MISSED)
}
