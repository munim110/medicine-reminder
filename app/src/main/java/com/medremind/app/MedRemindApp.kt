package com.aigeolab.medremind

import android.app.Application
import android.util.Log
import com.aigeolab.medremind.alarm.AlarmScheduler
import com.aigeolab.medremind.alarm.Notifications
import com.aigeolab.medremind.data.ScheduleRepository
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
