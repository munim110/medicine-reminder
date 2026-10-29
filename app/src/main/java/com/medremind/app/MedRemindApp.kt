package com.medremind.app

import android.app.Application
import android.util.Log
import com.medremind.app.alarm.AlarmScheduler
import com.medremind.app.alarm.Notifications
import com.medremind.app.cloud.CloudSession
import com.medremind.app.cloud.Guardian
import com.medremind.app.cloud.PatientSync
import com.medremind.app.data.AppDatabase
import com.medremind.app.data.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MedRemindApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)

        // Creating the database here also runs first-launch seeding, so the schedule exists
        // before anything tries to arm an alarm against it.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                ScheduleRepository.get(this@MedRemindApp).reconcileMissed()
                AlarmScheduler.rescheduleAll(this@MedRemindApp)
            }.onFailure { Log.e("MedRemindApp", "Startup scheduling failed", it) }
        }

        // Separate from the alarm work above, so a slow or failed sign-in can never delay arming.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                CloudSession.ensureSignedIn()
                if (CloudSession.isGoogle) Guardian.startWatching(this@MedRemindApp)
            }.onFailure { Log.w("MedRemindApp", "Anonymous sign-in failed; will retry on next use", it) }
        }

        // Any change to doses or the schedule is shared with the family, if sharing is on. Covers
        // every writer (alarm receivers, notification buttons, the UI) without each one remembering.
        PatientSync.schedulePeriodic(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            AppDatabase.get(this@MedRemindApp).invalidationTracker
                .createFlow("dose_logs", "dose_items", "medicines", "dose_groups", emitInitialState = false)
                .collect { PatientSync.request(this@MedRemindApp) }
        }
    }
}
