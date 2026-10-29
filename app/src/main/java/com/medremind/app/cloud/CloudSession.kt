package com.medremind.app.cloud

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.medremind.app.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * The cloud identity: an anonymous Firebase account, created silently on first use so nobody ever
 * has to log in on the patient's phone. A guardian links Google to it, which keeps the same
 * account and gives one that survives reinstalling or changing phones.
 *
 * Nothing in the alarm path calls this. Alarms keep working with no account and no network.
 */
object CloudSession {

    private const val TAG = "CloudSession"
    private val signInLock = Mutex()

    val uid: String? get() = Firebase.auth.currentUser?.uid

    val isGoogle: Boolean get() = Firebase.auth.currentUser?.isGoogle == true

    /** The signed-in user, updated on sign-in and Google linking. */
    val user: Flow<FirebaseUser?> = callbackFlow {
        val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        Firebase.auth.addAuthStateListener(listener)
        awaitClose { Firebase.auth.removeAuthStateListener(listener) }
    }

    /** Signs in anonymously if needed. Safe to call repeatedly; only one sign-in runs at a time. */
    suspend fun ensureSignedIn(): FirebaseUser = signInLock.withLock {
        Firebase.auth.currentUser ?: checkNotNull(Firebase.auth.signInAnonymously().await().user)
    }

    /** A current Firebase ID token for calling our own backend. Refreshed by the SDK when near expiry. */
    suspend fun idToken(): String {
        val user = ensureSignedIn()
        return checkNotNull(user.getIdToken(false).await().token) { "no ID token" }
    }

    enum class GoogleResult { SIGNED_IN, CANCELLED, NO_ACCOUNT, ACCOUNT_IN_USE, FAILED }

    /**
     * Shows the "Sign in with Google" sheet and attaches the account to this device's identity.
     * If that Google account already belongs to another identity (an earlier install), this device
     * switches to it, so a guardian keeps their linked family across phones — unless
     * [mayChangeIdentity] is false: a phone sharing its own doses would leave its family looking
     * at an identity it no longer writes to.
     */
    suspend fun signInWithGoogle(activity: Activity, mayChangeIdentity: Boolean = true): GoogleResult {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            return GoogleResult.CANCELLED
        } catch (e: NoCredentialException) {
            return GoogleResult.NO_ACCOUNT
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Google sign-in unavailable", e)
            return GoogleResult.FAILED
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleResult.FAILED
        }
        val firebaseCredential = GoogleAuthProvider.getCredential(GoogleIdTokenCredential.createFrom(credential.data).idToken, null)
        return try {
            val user = ensureSignedIn()
            try {
                user.linkWithCredential(firebaseCredential).await()
            } catch (e: FirebaseAuthUserCollisionException) {
                if (!mayChangeIdentity) return GoogleResult.ACCOUNT_IN_USE
                Firebase.auth.signInWithCredential(firebaseCredential).await()
            }
            // Security rules read the sign-in provider from the token, so make sure it is fresh.
            Firebase.auth.currentUser?.getIdToken(true)?.await()
            GoogleResult.SIGNED_IN
        } catch (e: Exception) {
            Log.w(TAG, "Linking Google failed", e)
            GoogleResult.FAILED
        }
    }

    /** Name to show the patient for this guardian: Google display name, else the email. */
    val displayName: String?
        get() = Firebase.auth.currentUser?.let { it.displayName?.takeIf(String::isNotBlank) ?: it.email }

    private val FirebaseUser.isGoogle: Boolean
        get() = providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }
}
