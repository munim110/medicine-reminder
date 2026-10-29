package com.medremind.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.medremind.app.alarm.AlarmScheduler
import com.medremind.app.alarm.AlarmService
import com.medremind.app.alarm.AlarmState
import com.medremind.app.alarm.DoseActions
import com.medremind.app.cloud.CloudPrefs
import com.medremind.app.cloud.CloudSession
import com.medremind.app.data.AlarmSettings
import com.medremind.app.data.LEGACY_SEED_MEDICINES
import com.medremind.app.data.DoseGroup
import com.medremind.app.data.DoseItem
import com.medremind.app.data.DoseLog
import com.medremind.app.data.DoseStatus
import com.medremind.app.data.Frequency
import com.medremind.app.data.GroupWithItems
import com.medremind.app.data.MedicineWithItems
import com.medremind.app.data.ScheduleRepository
import com.medremind.app.data.SlotMedicine
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One dose slot as it stands today: what is due, and what has happened to it. */
data class TodayRow(
    val group: GroupWithItems,
    /** Only what is due today: weekly and monthly medicines appear on their day. */
    val due: List<SlotMedicine>,
    val status: DoseStatus?,
    val scheduledAt: Long?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MedViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository.get(app)
    private val today = MutableStateFlow(LocalDate.now())

    val groups = repo.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val medicines: StateFlow<List<MedicineWithItems>> = repo.observeMedicines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings = repo.observeSettings()
        .map { it ?: AlarmSettings() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlarmSettings())

    /**
     * Keeps the escalation numbers coherent: the gap between rings must exceed how long the
     * alarm rings, and the give-up window must be at least one full gap.
     */
    fun updateSettings(candidate: AlarmSettings) = viewModelScope.launch {
        val ring = candidate.ringSeconds.coerceIn(60, 30 * 60)
        val retry = candidate.retryIntervalSeconds.coerceIn(ring + 60, 6 * 60 * 60)
        val giveUp = candidate.giveUpSeconds.coerceIn(retry, 12 * 60 * 60)
        repo.updateSettings(
            candidate.copy(
                ringSeconds = ring,
                retryIntervalSeconds = retry,
                giveUpSeconds = giveUp,
                snoozeMinutes = candidate.snoozeMinutes.coerceIn(1, 120),
            ),
        )
    }

    val history: StateFlow<List<DoseLog>> = repo.observeRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Slots with something due today, plus untimed slots so the আজ tab can ask for their time. */
    val todayRows = today
        .flatMapLatest { date ->
            combine(repo.observeGroups(), repo.observeLogsForDate(date)) { groups, logs ->
                val byGroup = logs.associateBy { it.groupId }
                groups
                    .filter { it.isDueOn(date) || byGroup.containsKey(it.group.id) }
                    .map { g ->
                        TodayRow(
                            group = g,
                            due = g.itemsDueOn(date),
                            status = byGroup[g.group.id]?.status,
                            scheduledAt = ScheduleRepository.scheduledMillis(g.group, date),
                        )
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Called when the app comes back to the foreground: roll the date and re-arm alarms. */
    fun refresh() {
        today.value = LocalDate.now()
        viewModelScope.launch {
            runCatching {
                repo.reconcileMissed()
                AlarmScheduler.rescheduleAll(getApplication())
            }
        }
    }

    fun mark(row: TodayRow, status: DoseStatus) = viewModelScope.launch {
        DoseActions.record(
            context = getApplication(),
            groupId = row.group.group.id,
            scheduledAt = row.scheduledAt ?: System.currentTimeMillis(),
            status = status,
        )
    }

    fun setTime(groupId: Long, hour: Int, minute: Int) = edit { repo.setTime(groupId, hour, minute) }

    fun setEnabled(groupId: Long, enabled: Boolean) = edit { repo.setEnabled(groupId, enabled) }

    fun addSlot(label: String, hour: Int, minute: Int) = edit {
        repo.addGroup(label.trim().ifBlank { "নতুন সময়" }, hour, minute)
    }

    fun deleteSlot(group: DoseGroup) = viewModelScope.launch {
        AlarmScheduler.cancel(getApplication(), group.id)
        AlarmScheduler.cancelRetry(getApplication(), group.id)
        if (AlarmState.ringingGroupId.value == group.id) AlarmService.stop(getApplication())
        repo.deleteGroup(group)
    }

    // ---- a guardian's phone that older versions seeded with one family's prescription ----

    private val prefs = CloudPrefs(app)
    private val legacySeedKept = MutableStateFlow(prefs.legacySeedKept)

    /**
     * True on a phone signed in to follow family that still rings for the prescription installs
     * before 1.1 came with. A guardian needs no alarms unless they add medicines of their own.
     */
    val offerClearLegacySeed: StateFlow<Boolean> =
        combine(CloudSession.user, medicines, legacySeedKept) { _, meds, kept ->
            !kept && CloudSession.isGoogle && !prefs.sharing &&
                meds.map { it.medicine.name }.toSet() == LEGACY_SEED_MEDICINES
        }
            .catch { emit(false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun clearLegacySeed() = edit { repo.deleteAllMedicines() }

    fun keepLegacySeed() {
        prefs.legacySeedKept = true
        legacySeedKept.value = true
    }

    fun addMedicine(groupId: Long, input: MedicineInput) = edit {
        if (input.name.isBlank()) return@edit
        repo.addToSlot(
            groupId = groupId,
            name = input.name,
            foodRule = input.foodRule,
            quantity = input.quantity,
            frequency = input.frequency,
            dayOfWeek = input.dayOfWeek,
            dayOfMonth = input.dayOfMonth,
            genericName = input.genericName,
        )
    }

    /** Saves the slot entry and, if the name changed, renames the medicine in every slot. */
    fun updateMedicine(entry: SlotMedicine, input: MedicineInput) = edit {
        if (input.name != entry.name) repo.renameMedicine(entry.medicine.id, input.name, input.genericName)
        repo.updateItem(
            entry.item.copy(
                quantity = input.quantity,
                foodRule = input.foodRule,
                frequency = input.frequency,
                dayOfWeek = input.dayOfWeek.takeIf { input.frequency == Frequency.WEEKLY },
                dayOfMonth = input.dayOfMonth.takeIf { input.frequency == Frequency.MONTHLY },
            ),
        )
    }

    fun removeFromSlot(item: DoseItem) = edit { repo.removeFromSlot(item) }

    /** Course over: removes the medicine from every slot. */
    fun deleteMedicine(medicineId: Long) = edit { repo.deleteMedicine(medicineId) }

    fun addStock(medicineId: Long, amount: Double) = viewModelScope.launch { repo.addStock(medicineId, amount) }

    fun setStock(medicineId: Long, stock: Double?) = viewModelScope.launch { repo.setStock(medicineId, stock) }

    /** Schedule edits change which days slots ring on, so every edit re-arms the alarms. */
    private fun edit(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        AlarmScheduler.rescheduleAll(getApplication())
    }
}
