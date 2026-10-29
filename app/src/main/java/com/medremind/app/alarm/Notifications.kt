package com.medremind.app.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.medremind.app.MainActivity
import com.medremind.app.R
import com.medremind.app.cloud.RemoteLog
import com.medremind.app.ui.AlarmActivity
import com.medremind.app.ui.Bn

object Notifications {

    const val CHANNEL_ALARM = "dose_alarm_v1"
    const val CHANNEL_FAMILY = "family_v1"
    const val ALARM_NOTIFICATION_ID = 1001
    private const val SCHEDULE_CHANGED_ID = 1002

    /** Extra on [MainActivity] intents: open the পরিবার tab. */
    const val EXTRA_OPEN_FAMILY = "com.medremind.app.OPEN_FAMILY"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(R.string.channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alarm_desc)
            // The foreground service owns sound and vibration so it can loop until confirmed.
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FAMILY,
                context.getString(R.string.channel_family_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_family_desc) },
        )
    }

    /** Patient's phone: a guardian's change has been applied, so nothing changes unannounced. */
    fun showScheduleChanged(context: Context, changes: List<String>) {
        val body = changes.joinToString("\n")
        notifyFamily(
            context,
            SCHEDULE_CHANGED_ID,
            NotificationCompat.Builder(context, CHANNEL_FAMILY)
                .setContentTitle(context.getString(R.string.notify_schedule_changed))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body)),
        )
    }

    /** Guardian's phone: a dose was not taken. */
    fun showMissedDose(context: Context, patientUid: String, patientName: String, log: RemoteLog) {
        val at = java.time.Instant.ofEpochMilli(log.scheduledAt).atZone(java.time.ZoneId.systemDefault())
        val body = context.getString(
            R.string.notify_missed_body,
            log.label,
            Bn.time(at.hour, at.minute),
            log.medicines,
        )
        notifyFamily(
            context,
            (patientUid + log.groupId + log.date).hashCode(),
            NotificationCompat.Builder(context, CHANNEL_FAMILY)
                .setContentTitle(context.getString(R.string.notify_missed_title, patientName))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body)),
        )
    }

    /** Guardian's phone: no news from the patient's phone for a day or more. */
    fun showPhoneQuiet(context: Context, patientUid: String, patientName: String, lastSeen: Long) {
        val body = context.getString(R.string.notify_quiet_body, Bn.ago(lastSeen))
        notifyFamily(
            context,
            ("quiet$patientUid").hashCode(),
            NotificationCompat.Builder(context, CHANNEL_FAMILY)
                .setContentTitle(context.getString(R.string.notify_quiet_title, patientName))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body)),
        )
    }

    private fun notifyFamily(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_FAMILY, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = builder
            .setSmallIcon(R.drawable.ic_pill)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    /** True when the OS will actually honour our full-screen intent (Android 14+ gates this). */
    fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    fun buildAlarmNotification(
        context: Context,
        groupId: Long,
        scheduledAt: Long,
        title: String,
        body: String,
        /** Labels the snooze button; null before the settings have been read. */
        snoozeMinutes: Int? = null,
    ): Notification {
        val fullScreen = PendingIntent.getActivity(
            context,
            groupId.toInt(),
            AlarmActivity.intent(context, groupId, scheduledAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(
                0,
                context.getString(R.string.action_taken),
                actionIntent(context, DoseActionReceiver.ACTION_TAKEN, groupId, scheduledAt),
            )
            .addAction(
                0,
                snoozeMinutes?.let { context.getString(R.string.action_snooze, Bn.num(it)) }
                    ?: context.getString(R.string.action_snooze_later),
                actionIntent(context, DoseActionReceiver.ACTION_SNOOZE, groupId, scheduledAt),
            )
            .build()
    }

    private fun actionIntent(
        context: Context,
        action: String,
        groupId: Long,
        scheduledAt: Long,
    ): PendingIntent {
        val intent = Intent(context, DoseActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("medremind://action/$action/$groupId")
            putExtra(AlarmScheduler.EXTRA_GROUP_ID, groupId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
        }
        return PendingIntent.getBroadcast(
            context,
            (action.hashCode() and 0xFFFF) + groupId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
