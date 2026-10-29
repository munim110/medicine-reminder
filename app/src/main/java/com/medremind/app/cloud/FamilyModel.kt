package com.medremind.app.cloud

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.firestore
import com.medremind.app.data.DoseStatus
import com.medremind.app.data.FoodRule
import com.medremind.app.data.Frequency
import java.security.SecureRandom

/*
 * Firestore layout (rules in firestore.rules):
 *
 *   pairCodes/{code}                 patient, name, expiresAt          — 15-minute pairing code
 *   patients/{patientUid}            name, lastSeen, health, slots, medicines
 *     logs/{groupId_date}            one dose outcome, mirrors dose_logs
 *     guardians/{guardianUid}        name, email, code, addedAt         — who may read this patient
 *     edits/{autoId}                 a guardian's change, applied and deleted by the patient's app
 *   users/{guardianUid}/linked/{patientUid}   name, addedAt            — a guardian's own list
 *
 * Only the patient's phone writes patient data. Guardians read it, and ask for changes via `edits`.
 */
internal object Paths {
    val db get() = Firebase.firestore
    fun patient(uid: String) = db.collection("patients").document(uid)
    fun logs(uid: String) = patient(uid).collection("logs")
    fun guardians(uid: String) = patient(uid).collection("guardians")
    fun edits(uid: String) = patient(uid).collection("edits")
    fun linked(guardianUid: String) = db.collection("users").document(guardianUid).collection("linked")
    fun pairCode(code: String) = db.collection("pairCodes").document(code)
}

/** Pairing codes: 8 characters with no look-alikes (0/O, 1/I/L), read aloud easily as XXXX-XXXX. */
object PairingCode {
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val LIFETIME_MILLIS = 15 * 60_000L

    fun generate(random: SecureRandom = SecureRandom()): String =
        (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun format(code: String): String = "${code.take(4)}-${code.drop(4)}"

    /** What a guardian typed → the stored form, or null if it cannot be a code. */
    fun normalize(typed: String): String? =
        typed.uppercase().filter { it.isLetterOrDigit() }.takeIf { it.length == 8 && it.all { c -> c in ALPHABET } }
}

data class GuardianInfo(val uid: String, val name: String, val email: String?)

/** One slot as the guardian sees it. */
data class RemoteSlot(
    val id: Long,
    val label: String,
    val hour: Int?,
    val minute: Int?,
    val enabled: Boolean,
    val items: List<RemoteItem>,
)

data class RemoteItem(
    val medicineId: Long,
    val name: String,
    val quantity: Double,
    val foodRule: FoodRule,
    val frequency: Frequency,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
)

data class RemoteMedicine(
    val id: Long,
    val name: String,
    val genericName: String?,
    val stock: Double?,
    val daysLeft: Int?,
    val low: Boolean,
    val scheduled: Boolean,
)

data class RemoteHealth(
    val notifications: Boolean = true,
    val exactAlarms: Boolean = true,
    val fullScreen: Boolean = true,
    val batteryUnrestricted: Boolean = true,
)

/** Everything a guardian sees about one patient, from `patients/{uid}`. */
data class PatientView(
    val uid: String,
    val name: String,
    val lastSeen: Timestamp?,
    val health: RemoteHealth,
    val slots: List<RemoteSlot>,
    val medicines: List<RemoteMedicine>,
) {
    companion object {
        /** Tolerant of missing fields: an older app version may have written the document. */
        @Suppress("UNCHECKED_CAST")
        fun from(doc: DocumentSnapshot): PatientView? {
            if (!doc.exists()) return null
            val health = doc.get("health") as? Map<String, Any?> ?: emptyMap()
            val slots = (doc.get("slots") as? List<Map<String, Any?>>).orEmpty().map { s ->
                RemoteSlot(
                    id = s.long("id") ?: 0,
                    label = s["label"] as? String ?: "",
                    hour = s.long("hour")?.toInt(),
                    minute = s.long("minute")?.toInt(),
                    enabled = s["enabled"] as? Boolean ?: true,
                    items = (s["items"] as? List<Map<String, Any?>>).orEmpty().map { i ->
                        RemoteItem(
                            medicineId = i.long("medicineId") ?: 0,
                            name = i["name"] as? String ?: "",
                            quantity = (i["quantity"] as? Number)?.toDouble() ?: 1.0,
                            foodRule = enumOr(i["foodRule"], FoodRule.ANY),
                            frequency = enumOr(i["frequency"], Frequency.DAILY),
                            dayOfWeek = i.long("dayOfWeek")?.toInt(),
                            dayOfMonth = i.long("dayOfMonth")?.toInt(),
                        )
                    },
                )
            }
            val medicines = (doc.get("medicines") as? List<Map<String, Any?>>).orEmpty().map { m ->
                RemoteMedicine(
                    id = m.long("id") ?: 0,
                    name = m["name"] as? String ?: "",
                    genericName = m["genericName"] as? String,
                    stock = (m["stock"] as? Number)?.toDouble(),
                    daysLeft = m.long("daysLeft")?.toInt(),
                    low = m["low"] as? Boolean ?: false,
                    scheduled = m["scheduled"] as? Boolean ?: true,
                )
            }
            return PatientView(
                uid = doc.id,
                name = doc.getString("name").orEmpty(),
                lastSeen = doc.getTimestamp("lastSeen"),
                health = RemoteHealth(
                    notifications = health["notifications"] as? Boolean ?: true,
                    exactAlarms = health["exactAlarms"] as? Boolean ?: true,
                    fullScreen = health["fullScreen"] as? Boolean ?: true,
                    batteryUnrestricted = health["batteryUnrestricted"] as? Boolean ?: true,
                ),
                slots = slots,
                medicines = medicines,
            )
        }
    }
}

/** One dose outcome as synced from the patient's history. */
data class RemoteLog(
    val groupId: Long,
    val date: String,
    val label: String,
    val medicines: String,
    val status: DoseStatus,
    val scheduledAt: Long,
    val actionedAt: Long,
) {
    companion object {
        fun from(doc: DocumentSnapshot): RemoteLog? {
            val date = doc.getString("date") ?: return null
            return RemoteLog(
                groupId = doc.getLong("groupId") ?: 0,
                date = date,
                label = doc.getString("label").orEmpty(),
                medicines = doc.getString("medicines").orEmpty(),
                status = enumOr(doc.getString("status"), DoseStatus.MISSED),
                scheduledAt = doc.getLong("scheduledAt") ?: 0,
                actionedAt = doc.getLong("actionedAt") ?: 0,
            )
        }
    }
}

private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

private inline fun <reified T : Enum<T>> enumOr(value: Any?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

/** Device-local state for sharing (patient side) and watching (guardian side). */
class CloudPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    /** On once the patient starts pairing; off after "stop sharing". Nothing uploads while off. */
    var sharing: Boolean
        get() = prefs.getBoolean("sharing", false)
        set(value) = prefs.edit().putBoolean("sharing", value).apply()

    /** How the family knows this patient, e.g. "আম্মা". */
    var patientName: String?
        get() = prefs.getString("patient_name", null)
        set(value) = prefs.edit().putString("patient_name", value).apply()

    var pairingCode: String?
        get() = prefs.getString("pairing_code", null)
        set(value) = prefs.edit().putString("pairing_code", value).apply()

    var pairingExpiresAt: Long
        get() = prefs.getLong("pairing_expires", 0)
        set(value) = prefs.edit().putLong("pairing_expires", value).apply()

    val pairingActive: Boolean get() = pairingCode != null && pairingExpiresAt > System.currentTimeMillis()

    /** Skips rewriting the patient document when nothing changed and the heartbeat is recent. */
    var lastSnapshotHash: Int
        get() = prefs.getInt("snapshot_hash", 0)
        set(value) = prefs.edit().putInt("snapshot_hash", value).apply()

    var lastSnapshotAt: Long
        get() = prefs.getLong("snapshot_at", 0)
        set(value) = prefs.edit().putLong("snapshot_at", value).apply()

    /** The patient accepted that scan photos go to an AI service. */
    var scanConsent: Boolean
        get() = prefs.getBoolean("scan_consent", false)
        set(value) = prefs.edit().putBoolean("scan_consent", value).apply()

    /** A guardian chose to keep the medicines an older version seeded, so stop offering to clear them. */
    var legacySeedKept: Boolean
        get() = prefs.getBoolean("legacy_seed_kept", false)
        set(value) = prefs.edit().putBoolean("legacy_seed_kept", value).apply()

    // ---- guardian side: per patient, what has already been notified ----

    fun watchMark(patientUid: String): Timestamp? {
        val seconds = prefs.getLong("watch_${patientUid}_s", -1)
        return if (seconds < 0) null else Timestamp(seconds, prefs.getInt("watch_${patientUid}_n", 0))
    }

    fun setWatchMark(patientUid: String, mark: Timestamp) =
        prefs.edit().putLong("watch_${patientUid}_s", mark.seconds).putInt("watch_${patientUid}_n", mark.nanoseconds).apply()

    fun quietNotified(patientUid: String): Boolean = prefs.getBoolean("quiet_$patientUid", false)

    fun setQuietNotified(patientUid: String, value: Boolean) = prefs.edit().putBoolean("quiet_$patientUid", value).apply()
}
