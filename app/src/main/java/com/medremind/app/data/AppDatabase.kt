package com.medremind.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DoseGroup::class, Medicine::class, DoseItem::class, DoseLog::class, AlarmSettings::class],
    version = 2,
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
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/**
 * Version 2 moves medicine names out of `dose_items` into a shared `medicines` table, so a medicine
 * taken in two slots is one record, and adds dose quantity and weekly/monthly days.
 *
 * Names that differ only in case or surrounding spaces become one medicine. Every existing slot
 * entry becomes a daily dose of one unit, which is what version 1 meant.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `medicines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `genericName` TEXT, `form` TEXT, `instructions` TEXT, `stock` REAL, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "INSERT INTO medicines (name, createdAt) " +
                "SELECT MIN(TRIM(medicineName)), $now FROM dose_items GROUP BY LOWER(TRIM(medicineName))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dose_items_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`groupId` INTEGER NOT NULL, `medicineId` INTEGER NOT NULL, " +
                "`quantity` REAL NOT NULL DEFAULT 1, `foodRule` TEXT NOT NULL, " +
                "`frequency` TEXT NOT NULL DEFAULT 'DAILY', `dayOfWeek` INTEGER, `dayOfMonth` INTEGER, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "FOREIGN KEY(`groupId`) REFERENCES `dose_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`medicineId`) REFERENCES `medicines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "INSERT INTO dose_items_new (id, groupId, medicineId, quantity, foodRule, frequency, sortOrder) " +
                "SELECT d.id, d.groupId, m.id, 1, d.foodRule, 'DAILY', d.sortOrder " +
                "FROM dose_items d JOIN medicines m ON LOWER(TRIM(d.medicineName)) = LOWER(m.name)",
        )
        db.execSQL("DROP TABLE dose_items")
        db.execSQL("ALTER TABLE dose_items_new RENAME TO dose_items")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_items_groupId` ON `dose_items` (`groupId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dose_items_medicineId` ON `dose_items` (`medicineId`)")
    }
}

/**
 * The medicines that installs before 1.1 were seeded with: one family's prescription. Only used to
 * spot a guardian's phone that still carries them, so it can offer to remove their alarms.
 */
val LEGACY_SEED_MEDICINES = setOf(
    "Empa 10", "Nidocard RH 2.6", "Vasteral MR", "Betaloc 50", "Clopid AS",
    "Atova 10", "Bizoran 5/40", "Siglimet 50/500",
)

/**
 * Seeds the database on first creation with the four customary slots, empty. Medicines come from a
 * scan or are typed in: a phone that only follows family must never ring.
 *
 * The morning slot ships with no time, so the app asks for the real one when a medicine needs it
 * rather than inventing one.
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
    }

    private data class Quad(
        val id: Long,
        val label: String,
        val hour: Int?,
        val minute: Int?,
        val sortOrder: Int,
    )
}
