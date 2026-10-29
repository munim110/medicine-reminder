package com.medremind.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.medremind.app.data.DoseStatus
import com.medremind.app.data.ScheduleRepository
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The single place a dose outcome is applied, shared by the alarm screen, the notification
 * action buttons, and the escalation timeout, so every path behaves identically.
 */
object DoseActions {

    suspend fun record(context: Context, groupId: Long, scheduledAt: Long, status: DoseStatus) {
        val repo = ScheduleRepository.get(context)
        val group = repo.group(groupId) ?: return
        val date = if (scheduledAt > 0) ScheduleRepository.localDate(scheduledAt) else LocalDate.now()
        repo.record(group, status, date, scheduledAt)

        // The dose is settled: stop ringing and stand down the escalation cycle.
        AlarmService.stop(context)
        AlarmScheduler.cancelRetry(context, groupId)
        AlarmScheduler.scheduleNext(context, group)
    }

    suspend fun snooze(context: Context, groupId: Long, scheduledAt: Long) {
        val minutes = ScheduleRepository.get(context).settings().snoozeMinutes
        AlarmService.stop(context)
        AlarmScheduler.snooze(context, groupId, scheduledAt, minutes)
    }
}

/** Handles the "খাওয়া হয়েছে" / "স্নুজ" buttons on the alarm notification. */
class DoseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val groupId = intent.getLongExtra(AlarmScheduler.EXTRA_GROUP_ID, -1L)
        if (groupId < 0) return
        val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)
        val appContext = context.applicationContext

        val status = when (intent.action) {
            ACTION_TAKEN -> DoseStatus.TAKEN
            ACTION_SKIPPED -> DoseStatus.SKIPPED
            ACTION_SNOOZE -> null
            else -> return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (status == null) {
                    DoseActions.snooze(appContext, groupId, scheduledAt)
                } else {
                    DoseActions.record(appContext, groupId, scheduledAt, status)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TAKEN = "com.medremind.app.TAKEN"
        const val ACTION_SKIPPED = "com.medremind.app.SKIPPED"
        const val ACTION_SNOOZE = "com.medremind.app.SNOOZE"
    }
}
