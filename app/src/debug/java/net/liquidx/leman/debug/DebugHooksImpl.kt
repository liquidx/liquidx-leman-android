package net.liquidx.leman.debug

import android.os.StrictMode
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.di.AppContainer
import net.liquidx.leman.di.DebugHooks
import net.liquidx.leman.ui.components.LemanTab
import net.liquidx.leman.ui.components.icons.TablerIcons
import net.liquidx.leman.util.LemanLog

/** Loaded reflectively by [AppContainer] in debug builds only (spec 08). */
@Suppress("unused")
class DebugHooksImpl : DebugHooks {

    val bus = DebugLogBus()
    val chaos = ChaosState()
    lateinit var container: AppContainer
        private set
    var switchable: SwitchableHermesClient? = null
        private set

    override val extraTabs: List<LemanTab> =
        listOf(LemanTab("debug", "debug", TablerIcons.Bug, danger = true))

    override fun interceptors(): List<okhttp3.Interceptor> = listOf(DebugInterceptor(bus, chaos))

    override fun attach(container: AppContainer) {
        this.container = container
        LemanLog.sink = { tag, message -> bus.logEvent(tag, System.currentTimeMillis() / 1000.0, message) }
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build(),
        )
    }

    override fun wrapClient(real: HermesClient): HermesClient =
        SwitchableHermesClient(real, FakeHermesServer(), chaos, bus).also { switchable = it }

    override fun registerDestinations(builder: NavGraphBuilder, navController: NavController) {
        builder.composable("debug") {
            DebugScreen(hooks = this@DebugHooksImpl, navController = navController)
        }
    }
}
