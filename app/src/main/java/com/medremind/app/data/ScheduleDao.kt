package com.medremind.app.data

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

    @Query("UPDATE dose_groups SET createdAt = :at WHERE id = :id")
    suspend fun setGroupTrackedFrom(id: Long, at: Long)

    @Upsert
    suspend fun upsertItem(item: DoseItem)

    @Delete
    suspend fun deleteItem(item: DoseItem)

    @Query("SELECT * FROM dose_items WHERE groupId = :groupId AND medicineId = :medicineId LIMIT 1")
    suspend fun findItem(groupId: Long, medicineId: Long): DoseItem?

    @Query("SELECT COUNT(*) FROM dose_items WHERE medicineId = :medicineId")
    suspend fun countItemsFor(medicineId: Long): Int

    // ---- medicines ----

    @Transaction
    @Query("SELECT * FROM medicines ORDER BY name COLLATE NOCASE")
    fun observeMedicines(): Flow<List<MedicineWithItems>>

    @Transaction
    @Query("SELECT * FROM medicines ORDER BY name COLLATE NOCASE")
    suspend fun getMedicines(): List<MedicineWithItems>

    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun getMedicine(id: Long): Medicine?

    @Query("SELECT * FROM medicines WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findMedicineByName(name: String): Medicine?

    @Insert
    suspend fun insertMedicine(medicine: Medicine): Long

    @Update
    suspend fun updateMedicine(medicine: Medicine)

    @Query("DELETE FROM medicines WHERE id = :id")
    suspend fun deleteMedicine(id: Long)

    @Query("DELETE FROM medicines")
    suspend fun deleteAllMedicines()

    @Query("UPDATE medicines SET stock = :stock WHERE id = :id")
    suspend fun setStock(id: Long, stock: Double?)

    /** Adds to tracked stock, starting tracking from zero if it was off. */
    @Query("UPDATE medicines SET stock = MAX(COALESCE(stock, 0) + :amount, 0) WHERE id = :id")
    suspend fun addStock(id: Long, amount: Double)

    /** Takes a dose out of tracked stock; untracked medicines are left alone. */
    @Query("UPDATE medicines SET stock = MAX(stock - :amount, 0) WHERE id = :id AND stock IS NOT NULL")
    suspend fun consumeStock(id: Long, amount: Double)

    // ---- history ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: DoseLog)

    @Query("SELECT * FROM dose_logs WHERE date = :date")
    fun observeLogsForDate(date: String): Flow<List<DoseLog>>

    @Query("SELECT * FROM dose_logs WHERE groupId = :groupId AND date = :date LIMIT 1")
    suspend fun findLog(groupId: Long, date: String): DoseLog?

    @Query("SELECT * FROM dose_logs ORDER BY scheduledAt DESC LIMIT :limit")
    fun observeRecentLogs(limit: Int = 200): Flow<List<DoseLog>>

    @Query("SELECT * FROM dose_logs WHERE synced = 0 AND date >= :sinceDate")
    suspend fun unsyncedLogs(sinceDate: String): List<DoseLog>

    @Query("UPDATE dose_logs SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /** After sharing stops, everything must upload again if it is turned back on. */
    @Query("UPDATE dose_logs SET synced = 0")
    suspend fun markAllUnsynced()

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
