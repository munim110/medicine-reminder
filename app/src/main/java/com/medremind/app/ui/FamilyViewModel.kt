package com.medremind.app.ui

import android.app.Activity
import android.app.Application
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.medremind.app.R
import com.medremind.app.cloud.CloudPrefs
import com.medremind.app.cloud.CloudSession
import com.medremind.app.cloud.Guardian
import com.medremind.app.cloud.GuardianInfo
import com.medremind.app.cloud.LinkedPatient
import com.medremind.app.cloud.PatientSync
import com.medremind.app.cloud.PatientView
import com.medremind.app.cloud.RemoteLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Pairing(val code: String, val expiresAt: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = CloudPrefs(app)

    // ---- patient side ----

    private val _sharing = MutableStateFlow(prefs.sharing)
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    private val _pairing = MutableStateFlow(currentPairing())
    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    val patientName: String? get() = prefs.patientName

    val guardians: StateFlow<List<GuardianInfo>> = sharing
        .flatMapLatest { on -> if (on) PatientSync.guardians().catch { emit(emptyList()) } else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun createCode(name: String) = work {
        val code = PatientSync.createPairingCode(getApplication(), name.trim())
        _pairing.value = Pairing(code, prefs.pairingExpiresAt)
        _sharing.value = true
    }

    fun removeGuardian(uid: String) = work { PatientSync.removeGuardian(getApplication(), uid) }

    fun stopSharing() = work {
        PatientSync.stopSharing(getApplication())
        _sharing.value = false
        _pairing.value = null
        toast(R.string.family_stopped)
    }

    /** The code screen hides itself once it expires; called when the tab is shown. */
    fun refreshPairing() {
        _pairing.value = currentPairing()
        _sharing.value = prefs.sharing
    }

    private fun currentPairing(): Pairing? =
        prefs.pairingCode?.takeIf { prefs.pairingActive }?.let { Pairing(it, prefs.pairingExpiresAt) }

    // ---- guardian side ----

    val isGoogle: StateFlow<Boolean> = CloudSession.user
        .map { CloudSession.isGoogle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudSession.isGoogle)

    val accountName: String? get() = CloudSession.displayName

    val linked: StateFlow<List<LinkedPatient>> = isGoogle
        .flatMapLatest { google -> if (google) Guardian.linkedPatients().catch { emit(emptyList()) } else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selected = MutableStateFlow<LinkedPatient?>(null)
    val selected: StateFlow<LinkedPatient?> = _selected.asStateFlow()

    val patient: StateFlow<PatientView?> = _selected
        .flatMapLatest { p -> if (p == null) flowOf(null) else Guardian.patient(p.uid).catch { emit(null) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val history: StateFlow<List<RemoteLog>> = _selected
        .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else Guardian.history(p.uid).catch { emit(emptyList()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingEdits: StateFlow<Int> = _selected
        .flatMapLatest { p -> if (p == null) flowOf(0) else Guardian.pendingEdits(p.uid).catch { emit(0) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun select(patient: LinkedPatient?) {
        _selected.value = patient
    }

    fun signIn(activity: Activity) = work {
        when (CloudSession.signInWithGoogle(activity, mayChangeIdentity = !prefs.sharing)) {
            CloudSession.GoogleResult.SIGNED_IN -> Guardian.startWatching(getApplication())
            CloudSession.GoogleResult.CANCELLED -> Unit
            CloudSession.GoogleResult.NO_ACCOUNT -> toast(R.string.google_no_account)
            CloudSession.GoogleResult.ACCOUNT_IN_USE -> toast(R.string.google_in_use_while_sharing)
            CloudSession.GoogleResult.FAILED -> toast(R.string.google_failed)
        }
    }

    fun link(code: String, onLinked: () -> Unit) = work {
        when (Guardian.link(getApplication(), code)) {
            Guardian.LinkResult.LINKED -> {
                toast(R.string.link_done)
                onLinked()
            }
            Guardian.LinkResult.BAD_CODE -> toast(R.string.link_bad_code)
            Guardian.LinkResult.OWN_CODE -> toast(R.string.link_own_code)
            Guardian.LinkResult.NOT_GOOGLE -> toast(R.string.google_failed)
            Guardian.LinkResult.FAILED -> toast(R.string.link_failed)
        }
    }

    fun unlink(patient: LinkedPatient) = work {
        Guardian.unlink(patient.uid)
        _selected.value = null
    }

    /** Sends a change request to the selected patient's phone. */
    fun sendEdit(edit: Map<String, Any?>) {
        val p = _selected.value ?: return
        work {
            Guardian.sendEdit(p.uid, edit)
            toast(R.string.edit_sent)
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.w("FamilyViewModel", "Family action failed", e)
                toast(R.string.family_offline)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun toast(@StringRes text: Int) = Toast.makeText(getApplication(), text, Toast.LENGTH_LONG).show()
}
