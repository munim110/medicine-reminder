package com.medremind.app.cloud

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.Source
import com.medremind.app.alarm.AlarmScheduler
import com.medremind.app.alarm.Notifications
import com.medremind.app.alarm.SetupHealth
import com.medremind.app.data.DoseLog
import com.medremind.app.data.FoodRule
import com.medremind.app.data.Frequency
import com.medremind.app.data.ScheduleRepository
import com.medremind.app.ui.Bn
import java.time.LocalDate
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * The patient's side of family sharing. Nothing leaves the phone until the patient creates a
 * pairing code, and "stop sharing" deletes everything uploaded.
 *
 * Sync runs in WorkManager, so it waits for a network and survives the app being closed:
 *  - after every dose outcome or schedule change (debounced),
 *  - hourly, as the heartbeat that tells the family the phone is alive, and to pick up their edits.
 */
object PatientSync {

    private const val TAG = "PatientSync"
    private const val WORK_NOW = "patient-sync"
    private const val WORK_PERIODIC = "patient-heartbeat"

    /** Days of history uploaded; the family's view covers a month. */
    private const val HISTORY_DAYS = 35L

    /** Rewrite the patient document at least this often even when nothing changed: the heartbeat. */
    private const val HEARTBEAT_MILLIS = 3 * 60 * 60_000L

    /** Queues a sync soon. Cheap no-op unless sharing is on. */
    fun request(context: Context) {
        if (!CloudPrefs(context).sharing) return
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, work)
    }

    fun schedulePeriodic(context: Context) {
        if (!CloudPrefs(context).sharing) return
        val work = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    /** Starts pairing: a code the guardian types into their phone, valid for 15 minutes. */
    suspend fun createPairingCode(context: Context, patientName: String): String {
        val uid = CloudSession.ensureSignedIn().uid
        val prefs = CloudPrefs(context)
        prefs.pairingCode?.let { old -> runCatching { Paths.pairCode(old).delete().await() } }
        val code = PairingCode.generate()
        val expiresAt = System.currentTimeMillis() + PairingCode.LIFETIME_MILLIS
        Paths.pairCode(code).set(
            mapOf("patient" to uid, "name" to patientName, "expiresAt" to Timestamp(Date(expiresAt))),
        ).await()
        prefs.patientName = patientName
        prefs.pairingCode = code
        prefs.pairingExpiresAt = expiresAt
        prefs.sharing = true
        // Upload now, so the guardian sees the schedule the moment they join.
        prefs.lastSnapshotHash = 0
        request(context)
        schedulePeriodic(context)
        return code
    }

    fun guardians(): Flow<List<GuardianInfo>> = callbackFlow {
        val uid = CloudSession.ensureSignedIn().uid
        val registration = Paths.guardians(uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Guardian list unavailable", error)
                return@addSnapshotListener
            }
            trySend(
                snapshot?.documents.orEmpty().map {
                    GuardianInfo(uid = it.id, name = it.getString("name").orEmpty(), email = it.getString("email"))
                },
            )
        }
        awaitClose { registration.remove() }
    }

    suspend fun removeGuardian(context: Context, guardianUid: String) {
        val uid = CloudSession.ensureSignedIn().uid
        Paths.guardians(uid).document(guardianUid).delete().await()
        request(context)
    }

    /** Removes every guardian and deletes everything this phone uploaded. */
    suspend fun stopSharing(context: Context) {
        val prefs = CloudPrefs(context)
        val uid = CloudSession.ensureSignedIn().uid
        prefs.pairingCode?.let { runCatching { Paths.pairCode(it).delete().await() } }
        deleteAll(Paths.guardians(uid))
        deleteAll(Paths.edits(uid))
        deleteAll(Paths.logs(uid))
        Paths.patient(uid).delete().await()
        prefs.sharing = false
        prefs.pairingCode = null
        prefs.lastSnapshotHash = 0
        ScheduleRepository.get(context).markAllUnsynced()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
    }

    /** One sync pass. Server reads only: a stale cache must never decide to delete or skip data. */
    suspend fun run(context: Context) {
        val prefs = CloudPrefs(context)
        if (!prefs.sharing) return
        val uid = CloudSession.ensureSignedIn().uid
        val repo = ScheduleRepository.get(context)

        prefs.pairingCode?.takeIf { !prefs.pairingActive }?.let { expired ->
            runCatching { Paths.pairCode(expired).delete().await() }
            prefs.pairingCode = null
        }

        val guardians = Paths.guardians(uid).get(Source.SERVER).await()
        if (guardians.isEmpty && !prefs.pairingActive) {
            // Everyone left (or nobody joined in time): don't keep health data in the cloud for no one.
            Log.i(TAG, "No guardians; removing shared data")
            stopSharing(context)
            return
        }

        RemoteEdits.applyPending(context, uid, guardians.documents.map { it.id }.toSet())

        val logs = repo.unsyncedLogs(LocalDate.now().minusDays(HISTORY_DAYS))
        logs.chunked(400).forEach { chunk ->
            val batch = Paths.db.batch()
            chunk.forEach { log -> batch.set(Paths.logs(uid).document(logId(log)), log.toRemote()) }
            batch.commit().await()
            repo.markSynced(chunk.map { it.id })
        }

        val snapshot = snapshot(context)
        val hash = snapshot.hashCode()
        val now = System.currentTimeMillis()
        val writeSnapshot = hash != prefs.lastSnapshotHash || now - prefs.lastSnapshotAt > HEARTBEAT_MILLIS
        if (writeSnapshot) {
            Paths.patient(uid).set(snapshot + mapOf("lastSeen" to FieldValue.serverTimestamp())).await()
            prefs.lastSnapshotHash = hash
            prefs.lastSnapshotAt = now
        }
        // Counts only: dose details are health data and stay out of logs.
        Log.i(TAG, "Synced ${logs.size} doses, ${guardians.size()} guardians, snapshot ${if (writeSnapshot) "written" else "unchanged"}")
    }

    /** One document per slot per day, so re-recording a dose overwrites rather than duplicates. */
    private fun logId(log: DoseLog) = "${log.groupId}_${log.date}"

    private fun DoseLog.toRemote() = mapOf(
        "groupId" to groupId,
        "date" to date,
        "label" to label,
        "medicines" to medicines,
        "status" to status.name,
        "scheduledAt" to scheduledAt,
        "actionedAt" to actionedAt,
        // Guardians' phones poll on this, so a dose recorded late is still noticed.
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    private suspend fun snapshot(context: Context): Map<String, Any?> {
        val repo = ScheduleRepository.get(context)
        val health = SetupHealth.check(context)
        return mapOf(
            "name" to CloudPrefs(context).patientName.orEmpty(),
            "appVersion" to runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull(),
            "health" to mapOf(
                "notifications" to health.notifications,
                "exactAlarms" to health.exactAlarms,
                "fullScreen" to health.fullScreen,
                "batteryUnrestricted" to health.batteryUnrestricted,
            ),
            "slots" to repo.groups().map { g ->
                mapOf(
                    "id" to g.group.id,
                    "label" to g.group.label,
                    "hour" to g.group.hour,
                    "minute" to g.group.minute,
                    "enabled" to g.group.enabled,
                    "items" to g.sortedItems.map { s ->
                        mapOf(
                            "medicineId" to s.medicine.id,
                            "name" to s.name,
                            "quantity" to s.item.quantity,
                            "foodRule" to s.item.foodRule.name,
                            "frequency" to s.item.frequency.name,
                            "dayOfWeek" to s.item.dayOfWeek,
                            "dayOfMonth" to s.item.dayOfMonth,
                        )
                    },
                )
            },
            "medicines" to repo.medicines().map { m ->
                mapOf(
                    "id" to m.medicine.id,
                    "name" to m.medicine.name,
                    "genericName" to m.medicine.genericName,
                    "stock" to m.medicine.stock,
                    "daysLeft" to m.daysLeft,
                    "low" to m.isLowOnStock,
                    "scheduled" to m.items.isNotEmpty(),
                )
            },
        )
    }

    private suspend fun deleteAll(collection: CollectionReference) {
        while (true) {
            val page = collection.limit(400).get(Source.SERVER).await()
            if (page.isEmpty) return
            val batch = Paths.db.batch()
            page.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        PatientSync.run(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Log.w("SyncWorker", "Sync failed; will retry", e)
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}

/**
 * Applies changes guardians asked for. The patient's phone is the only writer of its schedule, so
 * a guardian's edit becomes real only here, with bounds checked, and the patient is told.
 */
internal object RemoteEdits {

    /** Only edits from [guardianUids], the current guardians: one the patient removed may have left some queued. */
    suspend fun applyPending(context: Context, uid: String, guardianUids: Set<String>) {
        val edits = Paths.edits(uid).orderBy("createdAt", Query.Direction.ASCENDING).get(Source.SERVER).await()
        if (edits.isEmpty) return
        val repo = ScheduleRepository.get(context)
        val applied = mutableListOf<String>()
        for (doc in edits.documents) {
            if (doc.getString("by") !in guardianUids) {
                doc.reference.delete().await()
                continue
            }
            val summary = runCatching { apply(repo, doc.data.orEmpty()) }
                .onFailure { Log.w("RemoteEdits", "Skipping edit ${doc.id}", it) }
                .getOrNull()
            summary?.let { applied += "${doc.getString("byName").orEmpty()}: $it" }
            doc.reference.delete().await()
        }
        if (applied.isNotEmpty()) {
            AlarmScheduler.rescheduleAll(context)
            Notifications.showScheduleChanged(context, applied)
        }
    }

    /** Returns a one-line Bengali summary, or null when the edit was invalid or out of date. */
    private suspend fun apply(repo: ScheduleRepository, edit: Map<String, Any?>): String? {
        fun long(key: String) = (edit[key] as? Number)?.toLong()
        fun double(key: String) = (edit[key] as? Number)?.toDouble()
        return when (edit["type"]) {
            "set_time" -> {
                val slot = repo.group(long("slotId") ?: return null) ?: return null
                val hour = long("hour")?.toInt()?.takeIf { it in 0..23 } ?: return null
                val minute = long("minute")?.toInt()?.takeIf { it in 0..59 } ?: return null
                repo.setTime(slot.group.id, hour, minute)
                "${slot.group.label} এর সময় ${Bn.time(hour, minute)}"
            }
            "add_stock" -> {
                val medicine = repo.medicine(long("medicineId") ?: return null) ?: return null
                val amount = double("amount")?.takeIf { it > 0 && it <= 1000 } ?: return null
                repo.addStock(medicine.id, amount)
                "${medicine.name} ${Bn.qty(amount)}টি কেনা হয়েছে"
            }
            "delete_medicine" -> {
                val medicine = repo.medicine(long("medicineId") ?: return null) ?: return null
                repo.deleteMedicine(medicine.id)
                "${medicine.name} বাদ দেওয়া হয়েছে"
            }
            "add_medicine" -> {
                val slot = repo.group(long("slotId") ?: return null) ?: return null
                val name = (edit["name"] as? String)?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: return null
                val quantity = double("quantity")?.takeIf { it > 0 && it <= 10 } ?: 1.0
                val frequency = Frequency.entries.firstOrNull { it.name == edit["frequency"] } ?: Frequency.DAILY
                repo.addToSlot(
                    groupId = slot.group.id,
                    name = name,
                    foodRule = FoodRule.entries.firstOrNull { it.name == edit["foodRule"] } ?: FoodRule.ANY,
                    quantity = quantity,
                    frequency = frequency,
                    dayOfWeek = long("dayOfWeek")?.toInt()?.takeIf { it in 1..7 },
                    dayOfMonth = long("dayOfMonth")?.toInt()?.takeIf { it in 1..31 },
                    genericName = (edit["genericName"] as? String)?.take(200),
                )
                "${slot.group.label} এ $name যোগ হয়েছে"
            }
            else -> null
        }
    }
}
