package com.medremind.app.alarm

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.medremind.app.R
import com.medremind.app.data.ScheduleRepository
import com.medremind.app.ui.foodRuleLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Rings until the dose is confirmed.
 *
 * Sound and vibration live in a foreground service rather than the alarm Activity so that a
 * blocked or dismissed full-screen intent still produces something the user can hear.
 */
class AlarmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoStopJob: Job? = null
    private var ringing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopAlarm()
            stopSelf()
            return START_NOT_STICKY
        }

        val groupId = intent.getLongExtra(AlarmScheduler.EXTRA_GROUP_ID, -1L)
        if (groupId < 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val scheduledAt = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L)

        val notification = Notifications.buildAlarmNotification(
            context = this,
            groupId = groupId,
            scheduledAt = scheduledAt,
            title = getString(R.string.alarm_title),
            body = getString(R.string.alarm_body_loading),
        )

        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    Notifications.ALARM_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(Notifications.ALARM_NOTIFICATION_ID, notification)
            }
        }.isSuccess

        if (!started) {
            // Foreground start refused: at minimum get a heads-up notification in front of the user.
            Log.w(TAG, "startForeground refused; falling back to a plain notification")
            postNotification(notification)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ringing) {
            ringing = true
            AlarmState.setRinging(groupId)
            acquireWakeLock()
            startSound()
            startVibration()
            autoStopJob = scope.launch {
                val ringMillis = runCatching {
                    ScheduleRepository.get(this@AlarmService).settings().ringMillis
                }.getOrDefault(DEFAULT_RING_MILLIS)
                delay(ringMillis)
                Log.i(TAG, "Silencing after ${ringMillis / 1000}s; next escalation attempt is already armed")
                silenceButKeepNotification()
            }
        }

        scope.launch { updateNotificationDetails(groupId, scheduledAt) }
        return START_REDELIVER_INTENT
    }

    private suspend fun updateNotificationDetails(groupId: Long, scheduledAt: Long) {
        val group = runCatching { ScheduleRepository.get(this).group(groupId) }.getOrNull() ?: return
        val body = group.sortedItems.joinToString("\n") { item ->
            "• ${item.medicineName} — ${getString(foodRuleLabel(item.foodRule))}"
        }.ifBlank { getString(R.string.alarm_body_empty) }

        val updated = Notifications.buildAlarmNotification(
            context = this,
            groupId = groupId,
            scheduledAt = scheduledAt,
            title = getString(R.string.alarm_title_at, group.group.label),
            body = body,
        )
        postNotification(updated)
    }

    /** Posts the alarm notification, but only when the OS will actually accept it. */
    private fun postNotification(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; alarm will ring without a notification")
            return
        }
        try {
            NotificationManagerCompat.from(this)
                .notify(Notifications.ALARM_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification refused", e)
        }
    }

    private fun startSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(this@AlarmService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.e(TAG, "Alarm sound failed", it) }
    }

    private fun startVibration() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        vibrator = vib
        runCatching {
            val pattern = longArrayOf(0, 700, 800)
            vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }.onFailure { Log.e(TAG, "Vibration failed", it) }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "medremind:alarm").apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
        }
    }

    /** Stops the noise but leaves the notification on screen so the dose is not forgotten. */
    private fun silenceButKeepNotification() {
        stopNoise()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopNoise() {
        ringing = false
        AlarmState.setRinging(null)
        autoStopJob?.cancel()
        autoStopJob = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun stopAlarm() {
        stopNoise()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        runCatching {
            NotificationManagerCompat.from(this).cancel(Notifications.ALARM_NOTIFICATION_ID)
        }
    }

    override fun onDestroy() {
        stopNoise()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val ACTION_START = "com.medremind.app.RING"
        private const val ACTION_STOP = "com.medremind.app.SILENCE"

        /** Fallback ring length used only if settings cannot be read. */
        private const val DEFAULT_RING_MILLIS = 2 * 60_000L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 15 * 60_000L

        fun start(context: Context, groupId: Long, scheduledAt: Long) {
            val intent = Intent(context, AlarmService::class.java).apply {
                action = ACTION_START
                putExtra(AlarmScheduler.EXTRA_GROUP_ID, groupId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Could not start alarm service", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AlarmService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
            runCatching {
                NotificationManagerCompat.from(context).cancel(Notifications.ALARM_NOTIFICATION_ID)
            }
        }
    }
}
