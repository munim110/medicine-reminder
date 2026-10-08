package com.aigeolab.medremind.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aigeolab.medremind.R
import com.aigeolab.medremind.ui.AlarmActivity

object Notifications {

    const val CHANNEL_ALARM = "dose_alarm_v1"
    const val ALARM_NOTIFICATION_ID = 1001

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
                context.getString(R.string.action_snooze),
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
