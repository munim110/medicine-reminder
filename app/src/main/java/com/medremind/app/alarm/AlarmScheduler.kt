package com.medremind.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.medremind.app.MainActivity
import com.medremind.app.data.GroupWithItems
import com.medremind.app.data.ScheduleRepository
import java.time.ZoneId

/**
 * Schedules one alarm per dose slot, for the next day the slot has something due.
 *
 * Uses [AlarmManager.setAlarmClock] rather than setExact/setRepeating: it is the only API that
 * Doze and battery optimisation will not defer, and it surfaces the pending alarm in the system
 * status bar. Alarms are one-shot and re-armed after each fire, on boot, and on any schedule edit.
 */
object AlarmScheduler {

    const val EXTRA_GROUP_ID = "com.medremind.app.GROUP_ID"
    const val EXTRA_SCHEDULED_AT = "com.medremind.app.SCHEDULED_AT"

    /** On a retry the user asked for: it rings even past the give-up time, once. */
    const val EXTRA_SNOOZED = "com.medremind.app.SNOOZED"

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
            val armed = group.group.enabled && scheduleNext(context, group)
            if (!armed) {
                cancel(context, group.group.id)
                // Switched off or emptied mid-cycle: the dose being chased no longer exists.
                cancelRetry(context, group.group.id)
            }
        }
    }

    /**
     * Arms [group] for its next due occurrence strictly after [from], skipping any day whose dose
     * already has an outcome — taken early from the আজ tab, say. Returns false, arming nothing,
     * when the slot has no time or nothing is due in the coming months.
     */
    suspend fun scheduleNext(context: Context, group: GroupWithItems, from: Long = System.currentTimeMillis()): Boolean {
        val repo = ScheduleRepository.get(context)
        var after = from
        // At most today can already be answered, but allow for a backdated record or two.
        repeat(3) {
            val trigger = nextTrigger(group, after) ?: return false
            if (!repo.hasOutcome(group.group.id, trigger)) {
                scheduleAt(context, group.group.id, trigger)
                return true
            }
            after = trigger
        }
        return false
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
    fun scheduleRetry(
        context: Context,
        groupId: Long,
        scheduledAt: Long,
        triggerAtMillis: Long,
        snoozed: Boolean = false,
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = retryIntent(context, groupId, scheduledAt, snoozed)
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
            snoozed = true,
        )
    }

    private fun retryIntent(context: Context, groupId: Long, scheduledAt: Long, snoozed: Boolean = false): PendingIntent {
        val intent = Intent(context, RetryReceiver::class.java).apply {
            action = "com.medremind.app.DOSE_RETRY"
            data = Uri.parse("medremind://retry/$groupId")
            putExtra(EXTRA_GROUP_ID, groupId)
            putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(EXTRA_SNOOZED, snoozed)
        }
        return PendingIntent.getBroadcast(
            context,
            RETRY_REQUEST_OFFSET + groupId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The first time on or after [from]'s day when [group] has something due, or null. */
    internal fun nextTrigger(group: GroupWithItems, from: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val hour = group.group.hour ?: return null
        val minute = group.group.minute ?: return null
        val start = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        // Two months covers every monthly day, including the 31st after a short month.
        for (offset in 0L..62L) {
            val date = start.plusDays(offset)
            if (!group.isDueOn(date)) continue
            val at = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            if (at > from) return at
        }
        return null
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
