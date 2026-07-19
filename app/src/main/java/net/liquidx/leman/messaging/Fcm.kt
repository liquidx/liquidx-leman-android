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
