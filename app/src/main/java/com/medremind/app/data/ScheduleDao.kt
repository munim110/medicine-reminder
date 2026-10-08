package com.aigeolab.medremind.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    @Transaction
    @Query("SELECT * FROM dose_groups ORDER BY sortOrder ASC")
    fun observeGroups(): Flow<List<GroupWithItems>>

    @Transaction
    @Query("SELECT * FROM dose_groups ORDER BY sortOrder ASC")
    suspend fun getGroups(): List<GroupWithItems>

    @Transaction
    @Query("SELECT * FROM dose_groups WHERE id = :id")
    suspend fun getGroup(id: Long): GroupWithItems?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM dose_groups")
    suspend fun nextGroupSortOrder(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM dose_items WHERE groupId = :groupId")
    suspend fun nextItemSortOrder(groupId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: DoseGroup): Long

    @Update
    suspend fun updateGroup(group: DoseGroup)

    @Delete
    suspend fun deleteGroup(group: DoseGroup)

    @Query("UPDATE dose_groups SET hour = :hour, minute = :minute WHERE id = :id")
    suspend fun setGroupTime(id: Long, hour: Int, minute: Int)

    @Query("UPDATE dose_groups SET enabled = :enabled WHERE id = :id")
    suspend fun setGroupEnabled(id: Long, enabled: Boolean)

    @Upsert
    suspend fun upsertItem(item: DoseItem)

    @Delete
    suspend fun deleteItem(item: DoseItem)

    // ---- history ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: DoseLog)

    @Query("SELECT * FROM dose_logs WHERE date = :date")
    fun observeLogsForDate(date: String): Flow<List<DoseLog>>

    @Query("SELECT * FROM dose_logs WHERE groupId = :groupId AND date = :date LIMIT 1")
    suspend fun findLog(groupId: Long, date: String): DoseLog?

    @Query("SELECT * FROM dose_logs ORDER BY scheduledAt DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 200): Flow<List<DoseLog>>

    @Query("DELETE FROM dose_logs WHERE date < :beforeDate")
    suspend fun pruneLogsBefore(beforeDate: String)

    // ---- settings ----

    @Query("SELECT * FROM settings WHERE id = 1")
    fun observeSettings(): Flow<AlarmSettings?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun getSettings(): AlarmSettings?

    @Upsert
    suspend fun upsertSettings(settings: AlarmSettings)
}
