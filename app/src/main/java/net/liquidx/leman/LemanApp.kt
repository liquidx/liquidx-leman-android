package net.liquidx.leman

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
    }
}
