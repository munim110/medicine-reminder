package com.aigeolab.medremind.data

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

class ScheduleRepository(private val dao: ScheduleDao) {

    fun observeGroups(): Flow<List<GroupWithItems>> = dao.observeGroups()

    fun observeLogsForDate(date: LocalDate): Flow<List<DoseLog>> =
        dao.observeLogsForDate(date.toString())

    fun observeRecentLogs(): Flow<List<DoseLog>> = dao.observeRecentLogs()

    suspend fun groups(): List<GroupWithItems> = dao.getGroups()

    fun observeSettings(): Flow<AlarmSettings?> = dao.observeSettings()

    suspend fun settings(): AlarmSettings = dao.getSettings() ?: AlarmSettings()

    suspend fun updateSettings(settings: AlarmSettings) =
        dao.upsertSettings(settings.copy(id = AlarmSettings.SINGLETON_ID))

    /** True when this slot already has a recorded outcome for the day [scheduledAt] falls in. */
    suspend fun hasOutcome(groupId: Long, scheduledAt: Long): Boolean {
        val date = java.time.Instant.ofEpochMilli(scheduledAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return dao.findLog(groupId, date.toString()) != null
    }

    suspend fun group(id: Long): GroupWithItems? = dao.getGroup(id)

    suspend fun setTime(groupId: Long, hour: Int, minute: Int) = dao.setGroupTime(groupId, hour, minute)

    suspend fun setEnabled(groupId: Long, enabled: Boolean) = dao.setGroupEnabled(groupId, enabled)

    suspend fun addGroup(label: String, hour: Int, minute: Int): Long =
        dao.insertGroup(
            DoseGroup(
                label = label,
                hour = hour,
                minute = minute,
                sortOrder = dao.nextGroupSortOrder(),
            ),
        )

    suspend fun deleteGroup(group: DoseGroup) = dao.deleteGroup(group)

    suspend fun addItem(groupId: Long, name: String, rule: FoodRule) =
        dao.upsertItem(
            DoseItem(
                groupId = groupId,
                medicineName = name.trim(),
                foodRule = rule,
                sortOrder = dao.nextItemSortOrder(groupId),
            ),
        )

    suspend fun updateItem(item: DoseItem) = dao.upsertItem(item.copy(medicineName = item.medicineName.trim()))

    suspend fun deleteItem(item: DoseItem) = dao.deleteItem(item)

    /** Records the outcome of one dose. Re-recording the same slot on the same day overwrites. */
    suspend fun record(
        group: GroupWithItems,
        status: DoseStatus,
        date: LocalDate = LocalDate.now(),
        scheduledAt: Long = scheduledMillis(group.group, date) ?: System.currentTimeMillis(),
    ) {
        dao.insertLog(
            DoseLog(
                groupId = group.group.id,
                date = date.toString(),
                scheduledAt = scheduledAt,
                actionedAt = System.currentTimeMillis(),
                status = status,
                label = group.group.label,
                medicines = group.sortedItems.joinToString(", ") { it.medicineName },
            ),
        )
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
                        medicines = g.sortedItems.joinToString(", ") { it.medicineName },
                    ),
                )
            }
        }
        dao.pruneLogsBefore(today.minusDays(400).toString())
    }

    companion object {
        @Volatile
        private var instance: ScheduleRepository? = null

        fun get(context: Context): ScheduleRepository =
            instance ?: synchronized(this) {
                instance ?: ScheduleRepository(AppDatabase.get(context).scheduleDao())
                    .also { instance = it }
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
    }
}
