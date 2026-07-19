package net.liquidx.leman.messaging

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import net.liquidx.leman.LemanApp

/** Retryable device de-registration (opt-out half of I2). Delegates to [DeviceRegistrar]. */
class DeviceUnregistrationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LemanApp).container
        container.configurePushClient()
        return RegistrationResult.of(container.deviceRegistrar.unregister())
    }
}
