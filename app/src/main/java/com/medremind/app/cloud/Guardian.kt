package com.medremind.app.cloud

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import com.medremind.app.alarm.Notifications
import com.medremind.app.data.DoseStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class LinkedPatient(val uid: String, val name: String)

/**
 * The guardian's side. A guardian signs in with Google, types the patient's pairing code once, and
 * from then on reads that patient's schedule and history. Changes go as requests to the patient's
 * phone, which applies them.
 */
object Guardian {

    private const val TAG = "Guardian"
    private const val WORK_WATCH = "guardian-watch"

    /** No heartbeat for this long means the phone is off, offline, or the app has been stopped. */
    const val QUIET_MILLIS = 26 * 60 * 60_000L

    enum class LinkResult { LINKED, BAD_CODE, NOT_GOOGLE, OWN_CODE, FAILED }

    suspend fun link(context: Context, typed: String): LinkResult {
        val code = PairingCode.normalize(typed) ?: return LinkResult.BAD_CODE
        if (!CloudSession.isGoogle) return LinkResult.NOT_GOOGLE
        val me = CloudSession.ensureSignedIn()
        return try {
            val pairing = Paths.pairCode(code).get(Source.SERVER).await()
            val patient = pairing.getString("patient") ?: return LinkResult.BAD_CODE
            val expires = pairing.getTimestamp("expiresAt") ?: return LinkResult.BAD_CODE
            if (expires.toDate().time < System.currentTimeMillis()) return LinkResult.BAD_CODE
            if (patient == me.uid) return LinkResult.OWN_CODE
            val patientName = pairing.getString("name").orEmpty()

            val batch = Paths.db.batch()
            batch.set(
                Paths.guardians(patient).document(me.uid),
                mapOf(
                    "code" to code,
                    "name" to (CloudSession.displayName ?: ""),
                    "email" to me.email,
                    "addedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.set(
                Paths.linked(me.uid).document(patient),
                mapOf("name" to patientName, "addedAt" to FieldValue.serverTimestamp()),
            )
            batch.commit().await()
            startWatching(context)
            LinkResult.LINKED
        } catch (e: FirebaseFirestoreException) {
            Log.w(TAG, "Linking failed", e)
            // Rules reject an unknown or expired code as PERMISSION_DENIED.
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) LinkResult.BAD_CODE else LinkResult.FAILED
        } catch (e: Exception) {
            Log.w(TAG, "Linking failed", e)
            LinkResult.FAILED
        }
    }

    suspend fun unlink(patientUid: String) {
        val me = CloudSession.ensureSignedIn().uid
        runCatching { Paths.guardians(patientUid).document(me).delete().await() }
        Paths.linked(me).document(patientUid).delete().await()
    }

    fun linkedPatients(): Flow<List<LinkedPatient>> = callbackFlow {
        val me = CloudSession.ensureSignedIn().uid
        val registration = Paths.linked(me).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Linked list unavailable", error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents.orEmpty().map { LinkedPatient(it.id, it.getString("name").orEmpty()) })
        }
        awaitClose { registration.remove() }
    }

    /** Null until the patient's phone has uploaded, or if access was removed. */
    fun patient(patientUid: String): Flow<PatientView?> = callbackFlow {
        val registration = Paths.patient(patientUid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Patient unavailable", error)
                trySend(null)
                return@addSnapshotListener
            }
            trySend(snapshot?.let { PatientView.from(it) })
        }
        awaitClose { registration.remove() }
    }

    /** The last month of doses, newest first. */
    fun history(patientUid: String): Flow<List<RemoteLog>> = callbackFlow {
        val since = System.currentTimeMillis() - 31L * 24 * 60 * 60_000
        val registration = Paths.logs(patientUid)
            .whereGreaterThanOrEqualTo("scheduledAt", since)
            .orderBy("scheduledAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "History unavailable", error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().mapNotNull { RemoteLog.from(it) })
            }
        awaitClose { registration.remove() }
    }

    /** Changes sent but not yet picked up by the patient's phone. */
    fun pendingEdits(patientUid: String): Flow<Int> = callbackFlow {
        val registration = Paths.edits(patientUid).addSnapshotListener { snapshot, _ ->
            trySend(snapshot?.size() ?: 0)
        }
        awaitClose { registration.remove() }
    }

    /**
     * Asks the patient's phone to change something. [edit] holds `type` and its fields; see
     * `RemoteEdits` for what the phone accepts.
     */
    suspend fun sendEdit(patientUid: String, edit: Map<String, Any?>) {
        val me = CloudSession.ensureSignedIn()
        Paths.edits(patientUid).add(
            edit + mapOf(
                "by" to me.uid,
                "byName" to (CloudSession.displayName ?: ""),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    fun startWatching(context: Context) {
        val work = PeriodicWorkRequestBuilder<GuardianWatchWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_WATCH, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun stopWatching(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_WATCH)

    /**
     * One watch pass: notifies about doses newly synced as MISSED, and about a phone that has gone
     * quiet. Polling instead of push keeps the whole system on free tiers with no server code.
     */
    suspend fun watch(context: Context) {
        val me = CloudSession.uid ?: return
        val prefs = CloudPrefs(context)
        val patients = Paths.linked(me).get(Source.SERVER).await().documents
        if (patients.isEmpty()) {
            stopWatching(context)
            return
        }
        for (linked in patients) {
            val patientUid = linked.id
            val doc = try {
                Paths.patient(patientUid).get(Source.SERVER).await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    // The patient stopped sharing or removed this guardian: drop them from the list.
                    Log.i(TAG, "Access to a patient was removed; unlinking")
                    runCatching { Paths.linked(me).document(patientUid).delete().await() }
                }
                continue
            } catch (e: Exception) {
                continue
            }
            val view = PatientView.from(doc)
            val name = view?.name?.ifBlank { null } ?: linked.getString("name").orEmpty()

            val mark = prefs.watchMark(patientUid)
            if (mark == null) {
                // First look at this patient: start from now rather than replaying old history.
                prefs.setWatchMark(patientUid, Timestamp.now())
            } else {
                val fresh = Paths.logs(patientUid).whereGreaterThan("updatedAt", mark).get(Source.SERVER).await()
                fresh.documents
                    .mapNotNull { RemoteLog.from(it) }
                    .filter { it.status == DoseStatus.MISSED }
                    .forEach { Notifications.showMissedDose(context, patientUid, name, it) }
                fresh.documents.mapNotNull { it.getTimestamp("updatedAt") }.maxOrNull()?.let {
                    prefs.setWatchMark(patientUid, it)
                }
            }

            val lastSeen = view?.lastSeen?.toDate()?.time
            val quiet = lastSeen != null && System.currentTimeMillis() - lastSeen > QUIET_MILLIS
            if (quiet && !prefs.quietNotified(patientUid)) {
                Notifications.showPhoneQuiet(context, patientUid, name, lastSeen!!)
                prefs.setQuietNotified(patientUid, true)
            } else if (!quiet) {
                prefs.setQuietNotified(patientUid, false)
            }
        }
    }
}

class GuardianWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        Guardian.watch(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Log.w("GuardianWatch", "Watch failed", e)
        Result.retry()
    }
}
