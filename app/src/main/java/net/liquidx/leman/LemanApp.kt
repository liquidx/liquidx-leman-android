package net.liquidx.leman

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.liquidx.leman.di.AppContainer

class LemanApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.debugHooks?.attach(container)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.syncScheduler.onForeground()
            override fun onStop(owner: LifecycleOwner) = container.syncScheduler.onBackground()
        })
        container.appScope.launch {
            if (container.settings.settings.first().notificationsEnabled) {
                androidx.work.WorkManager.getInstance(this@LemanApp).enqueueUniqueWork(
                    "register-device",
                    androidx.work.ExistingWorkPolicy.KEEP,
                    androidx.work.OneTimeWorkRequestBuilder<net.liquidx.leman.messaging.DeviceRegistrationWorker>()
                        .setConstraints(
                            androidx.work.Constraints.Builder()
                                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build(),
                        )
                        .build(),
                )
            }
        }
    }
}
