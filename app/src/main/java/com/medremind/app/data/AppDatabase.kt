package com.medremind.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DoseGroup::class, DoseItem::class, DoseLog::class, AlarmSettings::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scheduleDao(): ScheduleDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "medremind.db")
                .addCallback(SeedCallback)
                .build()
    }
}

/**
 * Seeds the database on first creation with the prescription chart this app was built from.
 *
 * The morning slot intentionally ships with no time — the chart labelled it only "সকাল",
 * so the app asks for the real time on first launch rather than inventing one.
 */
private object SeedCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        val defaults = AlarmSettings()
        db.execSQL(
            "INSERT INTO settings (id, ringSeconds, retryIntervalSeconds, giveUpSeconds, snoozeMinutes) " +
                "VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                defaults.id,
                defaults.ringSeconds,
                defaults.retryIntervalSeconds,
                defaults.giveUpSeconds,
                defaults.snoozeMinutes,
            ),
        )

        // id, label, hour, minute, sortOrder
        val groups = listOf(
            Quad(1L, "সকাল", null, null, 0),
            Quad(2L, "দুপুর", 14, 30, 1),
            Quad(3L, "সন্ধ্যা", 19, 0, 2),
            Quad(4L, "রাত", 22, 0, 3),
        )
        // Tracking starts now: nothing before first launch may be reported as a missed dose.
        val createdAt = System.currentTimeMillis()
        for (g in groups) {
            db.execSQL(
                "INSERT INTO dose_groups (id, label, hour, minute, enabled, sortOrder, createdAt) " +
                    "VALUES (?, ?, ?, ?, 1, ?, ?)",
                arrayOf<Any?>(g.id, g.label, g.hour, g.minute, g.sortOrder, createdAt),
            )
        }

        // groupId, medicine, foodRule
        val items = listOf(
            Triple(1L, "Empa 10", FoodRule.EMPTY_STOMACH),
            Triple(1L, "Nidocard RH 2.6", FoodRule.AFTER_FOOD),
            Triple(1L, "Vasteral MR", FoodRule.AFTER_FOOD),
            Triple(1L, "Betaloc 50", FoodRule.AFTER_FOOD),

            Triple(2L, "Clopid AS", FoodRule.AFTER_FOOD),

            Triple(3L, "Atova 10", FoodRule.BEFORE_FOOD),
            Triple(3L, "Nidocard RH 2.6", FoodRule.BEFORE_FOOD),

            Triple(4L, "Betaloc 50", FoodRule.AFTER_FOOD),
            Triple(4L, "Bizoran 5/40", FoodRule.AFTER_FOOD),
            Triple(4L, "Vasteral MR", FoodRule.AFTER_FOOD),
            Triple(4L, "Siglimet 50/500", FoodRule.AFTER_FOOD),
        )
        val orderPerGroup = mutableMapOf<Long, Int>()
        for ((groupId, name, rule) in items) {
            val order = orderPerGroup.getOrDefault(groupId, 0)
            orderPerGroup[groupId] = order + 1
            db.execSQL(
                "INSERT INTO dose_items (groupId, medicineName, foodRule, sortOrder) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(groupId, name, rule.name, order),
            )
        }
    }

    private data class Quad(
        val id: Long,
        val label: String,
        val hour: Int?,
        val minute: Int?,
        val sortOrder: Int,
    )
}
