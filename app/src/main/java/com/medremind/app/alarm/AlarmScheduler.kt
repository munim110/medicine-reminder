package com.medremind.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.medremind.app.MainActivity
import com.medremind.app.data.DoseGroup
import com.medremind.app.data.ScheduleRepository
import java.time.LocalDate
import java.time.ZoneId

/**
 * Schedules one repeating daily alarm per dose slot.
 *
 * Uses [AlarmManager.setAlarmClock] rather than setExact/setRepeating: it is the only API that
 * Doze and battery optimisation will not defer, and it surfaces the pending alarm in the system
 * status bar. Alarms are one-shot and re-armed after each fire, on boot, and on any schedule edit.
 */
object AlarmScheduler {

    const val EXTRA_GROUP_ID = "com.medremind.app.GROUP_ID"
    const val EXTRA_SCHEDULED_AT = "com.medremind.app.SCHEDULED_AT"

    private const val TAG = "AlarmScheduler"
    private const val SHOW_INTENT_OFFSET = 100_000
    private const val RETRY_REQUEST_OFFSET = 500_000

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    /** Re-arms every enabled slot that has a time set. Safe to call repeatedly. */
    suspend fun rescheduleAll(context: Context) {
        val repo = ScheduleRepository.get(context)
        for (group in repo.groups()) {
            if (group.group.enabled && group.group.isTimeSet) {
                scheduleNext(context, group.group)
            } else {
                cancel(context, group.group.id)
            }
        }
    }

    /** Arms [group] for its next occurrence strictly after [from]. */
    fun scheduleNext(context: Context, group: DoseGroup, from: Long = System.currentTimeMillis()) {
        val trigger = nextTrigger(group, from) ?: return
        scheduleAt(context, group.id, trigger)
    }

    fun scheduleAt(context: Context, groupId: Long, triggerAtMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = alarmIntent(context, groupId, triggerAtMillis)
        try {
            if (canScheduleExact(context)) {
                val show = PendingIntent.getActivity(
                    context,
                    SHOW_INTENT_OFFSET + groupId.toInt(),
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), operation)
            } else {
                // Exact alarms not permitted: still fire, but the OS may delay us.
                am.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    5 * 60_000L,
                    operation,
                )
                Log.w(TAG, "Exact alarms unavailable; slot $groupId scheduled inexactly")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Denied scheduling alarm for slot $groupId", e)
        }
    }

    fun cancel(context: Context, groupId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(alarmIntent(context, groupId, 0L))
    }

    /**
     * Arms the next escalation attempt for a dose that is still unanswered.
     *
     * Deliberately a separate PendingIntent from the daily alarm: re-ringing today's dose must
     * never overwrite tomorrow's scheduled alarm.
     */
    fun scheduleRetry(context: Context, groupId: Long, scheduledAt: Long, triggerAtMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = retryIntent(context, groupId, scheduledAt)
        try {
            if (canScheduleExact(context)) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, null), operation)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMillis, 60_000L, operation)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Denied scheduling retry for slot $groupId", e)
        }
    }

    fun cancelRetry(context: Context, groupId: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(retryIntent(context, groupId, 0L))
    }

    /** Snooze rides the retry channel so it cannot disturb the daily alarm. */
    fun snooze(context: Context, groupId: Long, scheduledAt: Long, minutes: Int) {
        scheduleRetry(
            context = context,
            groupId = groupId,
            scheduledAt = scheduledAt,
            triggerAtMillis = System.currentTimeMillis() + minutes * 60_000L,
        )
    }

    private fun retryIntent(context: Context, groupId: Long, scheduledAt: Long): PendingIntent {
        val intent = Intent(context, RetryReceiver::class.java).apply {
            action = "com.medremind.app.DOSE_RETRY"
            data = Uri.parse("medremind://retry/$groupId")
            putExtra(EXTRA_GROUP_ID, groupId)
            putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
        }
        return PendingIntent.getBroadcast(
            context,
            RETRY_REQUEST_OFFSET + groupId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextTrigger(group: DoseGroup, from: Long): Long? {
        val hour = group.hour ?: return null
        val minute = group.minute ?: return null
        val zone = ZoneId.systemDefault()
        var candidate = LocalDate.now(zone).atTime(hour, minute).atZone(zone)
        if (candidate.toInstant().toEpochMilli() <= from) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }

    /**
     * A distinct [Uri] per slot keeps PendingIntents from being treated as equal — extras alone
     * are not part of intent equality, so without it every slot would share one alarm.
     */
    private fun alarmIntent(context: Context, groupId: Long, scheduledAt: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.medremind.app.DOSE_DUE"
            data = Uri.parse("medremind://dose/$groupId")
            putExtra(EXTRA_GROUP_ID, groupId)
            putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
        }
        return PendingIntent.getBroadcast(
            context,
            groupId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
