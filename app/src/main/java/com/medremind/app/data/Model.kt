package com.medremind.app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter

/** When a medicine must be taken relative to a meal. */
enum class FoodRule {
    EMPTY_STOMACH,
    BEFORE_FOOD,
    AFTER_FOOD,
    ANY,
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
     * When this slot started being tracked. Doses scheduled before it are never written off as
     * missed — the app cannot know what happened before it was managing them.
     */
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
) {
    val isTimeSet: Boolean get() = hour != null && minute != null
}

/** A single medicine within a [DoseGroup]. */
@Entity(
    tableName = "dose_items",
    foreignKeys = [
        ForeignKey(
            entity = DoseGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId")],
)
data class DoseItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val medicineName: String,
    val foodRule: FoodRule,
    val sortOrder: Int = 0,
)

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
    /** Stable id so a future server sync can deduplicate rows across devices. */
    @ColumnInfo(defaultValue = "") val uuid: String = java.util.UUID.randomUUID().toString(),
    /** False until this row has been pushed to the remote admin dashboard. */
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

/** A dose slot together with its medicines, ordered for display. */
data class GroupWithItems(
    @Embedded val group: DoseGroup,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val items: List<DoseItem> = emptyList(),
) {
    val sortedItems: List<DoseItem> get() = items.sortedBy { it.sortOrder }
}

class Converters {
    @TypeConverter
    fun fromFoodRule(value: FoodRule): String = value.name

    @TypeConverter
    fun toFoodRule(value: String): FoodRule =
        runCatching { FoodRule.valueOf(value) }.getOrDefault(FoodRule.ANY)

    @TypeConverter
    fun fromDoseStatus(value: DoseStatus): String = value.name

    @TypeConverter
    fun toDoseStatus(value: String): DoseStatus =
        runCatching { DoseStatus.valueOf(value) }.getOrDefault(DoseStatus.MISSED)
}
