package com.aigeolab.medremind.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aigeolab.medremind.alarm.AlarmScheduler
import com.aigeolab.medremind.alarm.DoseActions
import com.aigeolab.medremind.data.AlarmSettings
import com.aigeolab.medremind.data.DoseGroup
import com.aigeolab.medremind.data.DoseItem
import com.aigeolab.medremind.data.DoseLog
import com.aigeolab.medremind.data.DoseStatus
import com.aigeolab.medremind.data.FoodRule
import com.aigeolab.medremind.data.GroupWithItems
import com.aigeolab.medremind.data.ScheduleRepository
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One dose slot as it stands today: what is due, and what has happened to it. */
data class TodayRow(
    val group: GroupWithItems,
    val status: DoseStatus?,
    val scheduledAt: Long?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MedViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScheduleRepository.get(app)
    private val today = MutableStateFlow(LocalDate.now())

    val groups = repo.observeGroups()
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

    val history: kotlinx.coroutines.flow.StateFlow<List<DoseLog>> = repo.observeRecentLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todayRows = today
        .flatMapLatest { date ->
            combine(repo.observeGroups(), repo.observeLogsForDate(date)) { groups, logs ->
                val byGroup = logs.associateBy { it.groupId }
                groups.map { g ->
                    TodayRow(
                        group = g,
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

    fun setTime(groupId: Long, hour: Int, minute: Int) = viewModelScope.launch {
        repo.setTime(groupId, hour, minute)
        AlarmScheduler.rescheduleAll(getApplication())
    }

    fun setEnabled(groupId: Long, enabled: Boolean) = viewModelScope.launch {
        repo.setEnabled(groupId, enabled)
        AlarmScheduler.rescheduleAll(getApplication())
    }

    fun addSlot(label: String, hour: Int, minute: Int) = viewModelScope.launch {
        repo.addGroup(label.trim().ifBlank { "নতুন সময়" }, hour, minute)
        AlarmScheduler.rescheduleAll(getApplication())
    }

    fun deleteSlot(group: DoseGroup) = viewModelScope.launch {
        AlarmScheduler.cancel(getApplication(), group.id)
        repo.deleteGroup(group)
    }

    fun addMedicine(groupId: Long, name: String, rule: FoodRule) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        repo.addItem(groupId, name, rule)
    }

    fun updateMedicine(item: DoseItem) = viewModelScope.launch { repo.updateItem(item) }

    fun deleteMedicine(item: DoseItem) = viewModelScope.launch { repo.deleteItem(item) }
}
