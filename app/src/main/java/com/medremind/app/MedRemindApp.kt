package com.medremind.app

import android.app.Application
import android.util.Log
import com.medremind.app.alarm.AlarmScheduler
import com.medremind.app.alarm.Notifications
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
    }
}
