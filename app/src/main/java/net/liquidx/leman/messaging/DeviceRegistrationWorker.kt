package net.liquidx.leman.messaging

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import net.liquidx.leman.LemanApp

/** Retryable FCM-token registration. Delegates to [DeviceRegistrar]. */
class DeviceRegistrationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LemanApp).container
        container.configurePushClient()
        return RegistrationResult.of(container.deviceRegistrar.register())
    }
}

/** Pure Outcome → WorkManager Result mapping (unit-tested without a WorkManager runtime). */
object RegistrationResult {
    fun of(outcome: DeviceRegistrar.Outcome): ListenableWorker.Result = when (outcome) {
        DeviceRegistrar.Outcome.DONE, DeviceRegistrar.Outcome.GAVE_UP -> ListenableWorker.Result.success()
        DeviceRegistrar.Outcome.RETRY_LATER -> ListenableWorker.Result.retry()
    }
}
