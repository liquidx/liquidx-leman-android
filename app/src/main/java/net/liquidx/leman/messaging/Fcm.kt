package net.liquidx.leman.messaging

import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Current FCM registration token, or null if it can't be fetched. No coroutines-play-services dep. */
suspend fun fetchFcmToken(): String? = suspendCancellableCoroutine { cont ->
    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
        cont.resume(if (task.isSuccessful) task.result else null)
    }
}

/**
 * Deletes the local FCM registration token (opt-out half of the push client).
 * Best-effort: success and failure both just resume — there is no user-facing
 * error path for a local token delete.
 */
suspend fun deleteFcmToken() {
    suspendCancellableCoroutine<Unit> { cont ->
        FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {
            cont.resume(Unit)
        }
    }
}
