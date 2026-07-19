package net.liquidx.leman.messaging

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

/**
 * FCM entry point. A message is a lightweight "something changed" signal — its
 * payload is not trusted; the worker pulls fresh state from the gateway.
 */
class LemanMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val work = OneTimeWorkRequestBuilder<SyncNotifyWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("sync-notify", ExistingWorkPolicy.KEEP, work)
    }

    override fun onNewToken(token: String) {
        val work = OneTimeWorkRequestBuilder<DeviceRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("register-device", ExistingWorkPolicy.REPLACE, work)
    }
}
