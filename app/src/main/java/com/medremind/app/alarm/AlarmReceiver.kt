package com.medremind.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.medremind.app.data.DoseStatus
import com.medremind.app.data.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Fires when a dose slot first comes due. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val groupId = intent.getLongExtra(AlarmScheduler.EXTRA_GROUP_ID, -1L)
        if (groupId < 0) return
        val scheduledAt = intent
            .getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)
            .takeIf { it > 0 } ?: System.currentTimeMillis()

        val appContext = context.applicationContext

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = ScheduleRepository.get(appContext)

                // Don't ring for a dose already taken early, or a slot since emptied or switched
                // off. Bounded: the exact-alarm broadcast allows starting a foreground service only
                // briefly, so if the database is slow, ring anyway.
                val stale = withTimeoutOrNull(STALE_CHECK_MILLIS) { isStale(repo, groupId, scheduledAt) } ?: false
                if (stale) {
                    Log.i(TAG, "Slot $groupId needs nothing now; not ringing")
                    repo.group(groupId)?.let { AlarmScheduler.scheduleNext(appContext, it) }
                    return@launch
                }
                AlarmService.start(appContext, groupId, scheduledAt)

                // Re-arm immediately so tomorrow's dose is set even if this one is ignored.
                repo.group(groupId)?.let { AlarmScheduler.scheduleNext(appContext, it) }

                // Arm the next escalation attempt now rather than when the ring ends, so the
                // cycle survives the process being killed mid-alarm.
                val settings = repo.settings()
                settings.nextRetryAt(scheduledAt, System.currentTimeMillis())?.let { next ->
                    AlarmScheduler.scheduleRetry(appContext, groupId, scheduledAt, next)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-arm slot $groupId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
        const val STALE_CHECK_MILLIS = 2_000L
    }
}

/** True when nothing should ring for this dose: answered, or its slot gone, off, or empty that day. */
private suspend fun isStale(repo: ScheduleRepository, groupId: Long, scheduledAt: Long): Boolean {
    val group = repo.group(groupId) ?: return true
    return !group.group.enabled ||
        !group.isDueOn(ScheduleRepository.localDate(scheduledAt)) ||
        repo.hasOutcome(groupId, scheduledAt)
}

/**
 * Fires for each escalation attempt after an unanswered alarm.
 *
 * Either rings again, or — once the give-up window has closed — records the dose as missed
 * and ends the cycle.
 */
class RetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val groupId = intent.getLongExtra(AlarmScheduler.EXTRA_GROUP_ID, -1L)
        if (groupId < 0) return
        val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)
        val snoozed = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZED, false)
        val appContext = context.applicationContext

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = ScheduleRepository.get(appContext)

                // Already answered (from the app, or a notification button), or the slot was
                // deleted or switched off since? Stand down.
                val stale = scheduledAt <= 0 ||
                    (withTimeoutOrNull(STALE_CHECK_MILLIS) { isStale(repo, groupId, scheduledAt) } ?: false)
                if (stale) {
                    AlarmScheduler.cancelRetry(appContext, groupId)
                    return@launch
                }

                val settings = repo.settings()
                val now = System.currentTimeMillis()

                if (snoozed && settings.isPastGiveUp(scheduledAt, now)) {
                    // The user asked to be reminded later: ring once more, and only then give up.
                    AlarmService.start(appContext, groupId, scheduledAt)
                    val interval = settings.retryIntervalSeconds.coerceAtLeast(60) * 1000L
                    AlarmScheduler.scheduleRetry(appContext, groupId, scheduledAt, now + interval)
                    return@launch
                }

                if (settings.isPastGiveUp(scheduledAt, now)) {
                    Log.i(TAG, "Slot $groupId unanswered past give-up window; marking missed")
                    AlarmService.stop(appContext)
                    AlarmScheduler.cancelRetry(appContext, groupId)
                    val group = repo.group(groupId) ?: return@launch
                    repo.record(
                        group = group,
                        status = DoseStatus.MISSED,
                        date = ScheduleRepository.localDate(scheduledAt),
                        scheduledAt = scheduledAt,
                    )
                    return@launch
                }

                AlarmService.start(appContext, groupId, scheduledAt)
                settings.nextRetryAt(scheduledAt, now)?.let { next ->
                    AlarmScheduler.scheduleRetry(appContext, groupId, scheduledAt, next)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Retry for slot $groupId failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "RetryReceiver"
        const val STALE_CHECK_MILLIS = 2_000L
    }
}

/**
 * Alarms do not survive a reboot, a reinstall, or a clock change — re-arm everything
 * whenever the system tells us one of those happened.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            -> Unit
            else -> return
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ScheduleRepository.get(appContext).reconcileMissed()
                AlarmScheduler.rescheduleAll(appContext)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Reschedule after ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
