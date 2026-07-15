package net.liquidx.leman

import android.app.Application
import net.liquidx.leman.di.AppContainer

class LemanApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.debugHooks?.attach(container)
    }
}
