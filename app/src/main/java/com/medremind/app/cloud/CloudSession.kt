package com.medremind.app.cloud

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * The patient's cloud identity: an anonymous Firebase account, created silently on first use so
 * nobody ever has to log in on the patient's phone.
 *
 * Nothing in the alarm path calls this. Alarms keep working with no account and no network.
 */
object CloudSession {

    private val signInLock = Mutex()

    /** Signs in anonymously if needed. Safe to call repeatedly; only one sign-in runs at a time. */
    suspend fun ensureSignedIn() {
        signInLock.withLock {
            if (Firebase.auth.currentUser == null) Firebase.auth.signInAnonymously().await()
        }
    }

    /** A current Firebase ID token for calling our own backend. Refreshed by the SDK when near expiry. */
    suspend fun idToken(): String {
        ensureSignedIn()
        val user = checkNotNull(Firebase.auth.currentUser) { "sign-in produced no user" }
        return checkNotNull(user.getIdToken(false).await().token) { "no ID token" }
    }
}
