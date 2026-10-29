package com.medremind.app.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * The phone settings an alarm needs in order to ring on time. Shown as banners on the আজ tab and
 * reported to the family, who often can fix what the patient cannot.
 */
data class SetupHealth(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val fullScreen: Boolean,
    val batteryUnrestricted: Boolean,
) {
    val allGood: Boolean get() = notifications && exactAlarms && fullScreen && batteryUnrestricted

    companion object {
        fun check(context: Context) = SetupHealth(
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            exactAlarms = AlarmScheduler.canScheduleExact(context),
            fullScreen = Notifications.canUseFullScreen(context),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) != false,
        )
    }
}
