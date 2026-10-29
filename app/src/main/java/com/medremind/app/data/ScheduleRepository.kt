package com.medremind.app.data

import android.content.Context
import androidx.room.withTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

class ScheduleRepository(private val db: AppDatabase) {

    private val dao = db.scheduleDao()

    fun observeGroups(): Flow<List<GroupWithItems>> = dao.observeGroups()

    fun observeMedicines(): Flow<List<MedicineWithItems>> = dao.observeMedicines()

    fun observeLogsForDate(date: LocalDate): Flow<List<DoseLog>> =
        dao.observeLogsForDate(date.toString())

    fun observeRecentLogs(): Flow<List<DoseLog>> = dao.observeRecentLogs()

    suspend fun groups(): List<GroupWithItems> = dao.getGroups()

    suspend fun medicines(): List<MedicineWithItems> = dao.getMedicines()

    fun observeSettings(): Flow<AlarmSettings?> = dao.observeSettings()

    suspend fun settings(): AlarmSettings = dao.getSettings() ?: AlarmSettings()

    suspend fun updateSettings(settings: AlarmSettings) =
        dao.upsertSettings(settings.copy(id = AlarmSettings.SINGLETON_ID))

    /** True when this slot already has a recorded outcome for the day [scheduledAt] falls in. */
    suspend fun hasOutcome(groupId: Long, scheduledAt: Long): Boolean =
        dao.findLog(groupId, localDate(scheduledAt).toString()) != null

    suspend fun group(id: Long): GroupWithItems? = dao.getGroup(id)

    suspend fun setTime(groupId: Long, hour: Int, minute: Int) = db.withTransaction {
        dao.setGroupTime(groupId, hour, minute)
        trackFromNow(groupId)
    }

    suspend fun setEnabled(groupId: Long, enabled: Boolean) = db.withTransaction {
        dao.setGroupEnabled(groupId, enabled)
        // The days it was switched off were not missed.
        if (enabled) trackFromNow(groupId)
    }

    suspend fun addGroup(label: String, hour: Int?, minute: Int?): Long =
        dao.insertGroup(
            DoseGroup(
                label = label,
                hour = hour,
                minute = minute,
                sortOrder = dao.nextGroupSortOrder(),
            ),
        )

    suspend fun deleteGroup(group: DoseGroup) = db.withTransaction {
        val medicineIds = dao.getGroup(group.id)?.items?.map { it.medicine.id }.orEmpty()
        dao.deleteGroup(group)
        medicineIds.forEach { deleteIfUnused(it) }
    }

    /**
     * Puts a medicine in a slot. A medicine of the same name (ignoring case) is reused rather than
     * duplicated, and if it is already in this slot its entry is updated instead of added twice.
     */
    suspend fun addToSlot(
        groupId: Long,
        name: String,
        foodRule: FoodRule,
        quantity: Double = 1.0,
        frequency: Frequency = Frequency.DAILY,
        dayOfWeek: Int? = null,
        dayOfMonth: Int? = null,
        genericName: String? = null,
    ) = db.withTransaction {
        val medicineId = findOrCreateMedicine(name, genericName = genericName)
        putInSlot(groupId, medicineId, foodRule, quantity, frequency, dayOfWeek, dayOfMonth)
    }

    suspend fun updateItem(item: DoseItem) = db.withTransaction {
        val old = dao.findItem(item.groupId, item.medicineId)
        dao.upsertItem(item)
        if (old == null || !old.sameDays(item)) trackFromNow(item.groupId)
    }

    /** Clears every medicine, keeping the slots and history. For a phone that only follows family. */
    suspend fun deleteAllMedicines() = dao.deleteAllMedicines()

    /** Renames everywhere the medicine is taken. */
    suspend fun renameMedicine(medicineId: Long, name: String, genericName: String? = null) {
        val medicine = dao.getMedicine(medicineId) ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dao.updateMedicine(
            medicine.copy(
                name = trimmed,
                // A new name may be a different product, so a remembered ingredient no longer applies.
                genericName = genericName ?: medicine.genericName.takeIf { trimmed.equals(medicine.name, true) },
            ),
        )
    }

    /** Takes a medicine out of one slot. If no slot uses it any more, the medicine is deleted too. */
    suspend fun removeFromSlot(item: DoseItem) = db.withTransaction {
        dao.deleteItem(item)
        deleteIfUnused(item.medicineId)
    }

    /** Removes a medicine from every slot. Used when a course is over. */
    suspend fun deleteMedicine(medicineId: Long) = dao.deleteMedicine(medicineId)

    suspend fun medicine(id: Long): Medicine? = dao.getMedicine(id)

    suspend fun setStock(medicineId: Long, stock: Double?) =
        dao.setStock(medicineId, stock?.coerceAtLeast(0.0))

    suspend fun addStock(medicineId: Long, amount: Double) = dao.addStock(medicineId, amount)

    /**
     * Records the outcome of one dose. Re-recording the same slot on the same day overwrites.
     * The first time a dose is marked TAKEN, its units come out of tracked stock.
     */
    suspend fun record(
        group: GroupWithItems,
        status: DoseStatus,
        date: LocalDate = LocalDate.now(),
        scheduledAt: Long = scheduledMillis(group.group, date) ?: System.currentTimeMillis(),
    ) = db.withTransaction {
        val due = group.itemsDueOn(date).ifEmpty { group.sortedItems }
        val previous = dao.findLog(group.group.id, date.toString())
        dao.insertLog(
            DoseLog(
                groupId = group.group.id,
                date = date.toString(),
                scheduledAt = scheduledAt,
                actionedAt = System.currentTimeMillis(),
                status = status,
                label = group.group.label,
                medicines = describe(due),
            ),
        )
        if (status == DoseStatus.TAKEN && previous?.status != DoseStatus.TAKEN) {
            due.forEach { dao.consumeStock(it.medicine.id, it.item.quantity) }
        }
    }

    /**
     * Writes MISSED rows for slots whose time passed without any recorded outcome, so history
     * reflects reality rather than silently omitting skipped days.
     *
     * The grace period is the configured give-up window, so a dose still inside its escalation
     * cycle is never written off early.
     */
    suspend fun reconcileMissed(daysBack: Int = 7) {
        val graceMillis = settings().giveUpMillis
        val now = System.currentTimeMillis()
        val groups = dao.getGroups()
        val today = LocalDate.now()
        for (offset in 0..daysBack) {
            val date = today.minusDays(offset.toLong())
            for (g in groups) {
                if (!g.group.enabled) continue
                // Nothing was due (a weekly medicine's off day, or an empty slot): nothing to miss.
                if (!g.isDueOn(date)) continue
                val at = scheduledMillis(g.group, date) ?: continue
                // Never invent history from before this slot existed.
                if (at < g.group.createdAt) continue
                if (at > now - graceMillis) continue
                if (dao.findLog(g.group.id, date.toString()) != null) continue
                dao.insertLog(
                    DoseLog(
                        groupId = g.group.id,
                        date = date.toString(),
                        scheduledAt = at,
                        actionedAt = 0L,
                        status = DoseStatus.MISSED,
                        label = g.group.label,
                        medicines = describe(g.itemsDueOn(date)),
                    ),
                )
            }
        }
        dao.pruneLogsBefore(today.minusDays(400).toString())
    }

    /**
     * Adds reviewed prescription medicines. Each time of day goes to the existing slot with that
     * name, or one whose time falls in that part of the day; failing both, a slot is created with
     * no time, which the আজ tab then asks for rather than guessing.
     *
     * Returns how many slots had to be created.
     */
    suspend fun import(entries: List<ImportEntry>): Int = db.withTransaction {
        var created = 0
        val slotFor = mutableMapOf<SlotKind, Long>()
        for (entry in entries) {
            val medicineId = findOrCreateMedicine(
                name = entry.name,
                genericName = entry.genericName,
                form = entry.form,
                instructions = entry.instructions,
            )
            for ((kind, quantity) in entry.doses) {
                if (quantity <= 0.0) continue
                val groupId = slotFor.getOrPut(kind) {
                    findSlot(kind) ?: addGroup(kind.label, null, null).also { created++ }
                }
                putInSlot(
                    groupId = groupId,
                    medicineId = medicineId,
                    foodRule = entry.foodRule,
                    quantity = quantity,
                    frequency = entry.frequency,
                    dayOfWeek = entry.dayOfWeek,
                    dayOfMonth = entry.dayOfMonth,
                )
            }
        }
        created
    }

    // ---- sync support ----

    suspend fun unsyncedLogs(sinceDate: LocalDate): List<DoseLog> = dao.unsyncedLogs(sinceDate.toString())

    suspend fun markSynced(ids: List<Long>) = ids.chunked(500).forEach { dao.markSynced(it) }

    suspend fun markAllUnsynced() = dao.markAllUnsynced()

    private suspend fun findSlot(kind: SlotKind): Long? {
        val groups = dao.getGroups().map { it.group }
        return (
            groups.firstOrNull { it.label.trim() == kind.label }
                ?: groups.firstOrNull { it.hour != null && kind.covers(it.hour) }
            )?.id
    }

    private suspend fun findOrCreateMedicine(
        name: String,
        genericName: String? = null,
        form: String? = null,
        instructions: String? = null,
    ): Long {
        val trimmed = name.trim()
        val existing = dao.findMedicineByName(trimmed)
        if (existing != null) {
            // Fill in what we have learned, never overwrite what the family typed.
            val merged = existing.copy(
                genericName = existing.genericName ?: genericName,
                form = existing.form ?: form,
                instructions = existing.instructions ?: instructions,
            )
            if (merged != existing) dao.updateMedicine(merged)
            return existing.id
        }
        return dao.insertMedicine(
            Medicine(name = trimmed, genericName = genericName, form = form, instructions = instructions),
        )
    }

    private suspend fun putInSlot(
        groupId: Long,
        medicineId: Long,
        foodRule: FoodRule,
        quantity: Double,
        frequency: Frequency,
        dayOfWeek: Int?,
        dayOfMonth: Int?,
    ) {
        val existing = dao.findItem(groupId, medicineId)
        val item = DoseItem(
            id = existing?.id ?: 0,
            groupId = groupId,
            medicineId = medicineId,
            quantity = quantity,
            foodRule = foodRule,
            frequency = frequency,
            dayOfWeek = dayOfWeek.takeIf { frequency == Frequency.WEEKLY },
            dayOfMonth = dayOfMonth.takeIf { frequency == Frequency.MONTHLY },
            sortOrder = existing?.sortOrder ?: dao.nextItemSortOrder(groupId),
        )
        dao.upsertItem(item)
        if (existing == null || !existing.sameDays(item)) trackFromNow(groupId)
    }

    private suspend fun trackFromNow(groupId: Long) = dao.setGroupTrackedFrom(groupId, System.currentTimeMillis())

    private fun DoseItem.sameDays(other: DoseItem) =
        frequency == other.frequency && dayOfWeek == other.dayOfWeek && dayOfMonth == other.dayOfMonth

    private suspend fun deleteIfUnused(medicineId: Long) {
        if (dao.countItemsFor(medicineId) == 0) dao.deleteMedicine(medicineId)
    }

    companion object {
        @Volatile
        private var instance: ScheduleRepository? = null

        fun get(context: Context): ScheduleRepository =
            instance ?: synchronized(this) {
                instance ?: ScheduleRepository(AppDatabase.get(context)).also { instance = it }
            }

        /** Wall-clock millis for [group]'s slot on [date], or null if its time is unset. */
        fun scheduledMillis(group: DoseGroup, date: LocalDate): Long? {
            val hour = group.hour ?: return null
            val minute = group.minute ?: return null
            return date.atTime(hour, minute)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }

        fun localDate(millis: Long): LocalDate =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

        /** The history snapshot of what a dose contained, e.g. `Empa 10, Janmet 50/500 (½টি)`. */
        fun describe(items: List<SlotMedicine>): String =
            items.joinToString(", ") { Quantities.describe(it.name, it.item.quantity) }
    }
}
