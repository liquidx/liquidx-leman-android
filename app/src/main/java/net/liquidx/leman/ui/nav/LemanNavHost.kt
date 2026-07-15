package net.liquidx.leman.ui.nav

import android.content.Intent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.liquidx.leman.BuildConfig
import net.liquidx.leman.di.AppContainer
import net.liquidx.leman.ui.components.ConfigTab
import net.liquidx.leman.ui.components.LemanTab
import net.liquidx.leman.ui.components.ThreadsTab
import net.liquidx.leman.ui.config.ConfigScreen
import net.liquidx.leman.ui.config.ConfigViewModel
import net.liquidx.leman.ui.format.TimeFormat
import net.liquidx.leman.ui.newthread.NewThreadScreen
import net.liquidx.leman.ui.newthread.NewThreadViewModel
import net.liquidx.leman.ui.thread.ThreadScreen
import net.liquidx.leman.ui.thread.ThreadViewModel
import net.liquidx.leman.ui.threads.ThreadsScreen
import net.liquidx.leman.ui.threads.ThreadsViewModel

object Routes {
    const val THREADS = "threads"
    const val THREAD = "thread/{threadId}"
    const val NEW_THREAD = "newThread"
    const val CONFIG = "config"

    fun thread(id: String) = "thread/$id"
}

/** Live "HH:mm" for the status row, updating each minute. */
@Composable
fun rememberWallClock(): String {
    val zone = remember { ZoneId.systemDefault() }
    val clock by produceState(TimeFormat.clock(System.currentTimeMillis(), zone)) {
        while (true) {
            value = TimeFormat.clock(System.currentTimeMillis(), zone)
            delay(15_000)
        }
    }
    return clock
}

/** Transitions are cuts or fades ≤150ms — sharp and mechanical (spec 01). */
@Composable
fun LemanNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
    onRevealKey: (onAuthed: () -> Unit) -> Unit = { it() },
    onShareExport: (String) -> Unit = {},
) {
    val clock = rememberWallClock()
    val context = LocalContext.current
    val tabs = remember(container) {
        listOf(ThreadsTab, ConfigTab) + container.debugHooks?.extraTabs.orEmpty()
    }
    val fade = tween<Float>(net.liquidx.leman.ui.theme.LemanMotion.screenFadeMillis)

    NavHost(
        navController = navController,
        startDestination = Routes.THREADS,
        enterTransition = { fadeIn(fade) },
        exitTransition = { fadeOut(fade) },
        popEnterTransition = { fadeIn(fade) },
        popExitTransition = { fadeOut(fade) },
    ) {
        composable(Routes.THREADS) {
            val vm: ThreadsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { ThreadsViewModel(container.threadRepository, container.connectionManager) }
                },
            )
            val state by vm.state.collectAsStateWithLifecycle()
            ThreadsScreen(
                state = state,
                clock = clock,
                tabs = tabs,
                onEvent = vm::onEvent,
                onOpenThread = { navController.navigate(Routes.thread(it)) },
                onNewThread = { navController.navigate(Routes.NEW_THREAD) },
                onOpenConfig = { navController.navigate(Routes.CONFIG) { launchSingleTop = true } },
                onSelectTab = { tab -> navController.navigate(tab.id) { launchSingleTop = true } },
            )
        }

        composable(
            Routes.THREAD,
            deepLinks = listOf(navDeepLink { uriPattern = "leman://thread/{threadId}" }),
        ) { entry ->
            val threadId = entry.arguments?.getString("threadId") ?: return@composable
            val vm: ThreadViewModel = viewModel(
                key = "thread-$threadId",
                factory = viewModelFactory {
                    initializer {
                        ThreadViewModel(
                            repo = container.threadRepository,
                            connectionManager = container.connectionManager,
                            settingsStore = container.settings,
                            savedState = createSavedStateHandle(),
                            threadId = threadId,
                        )
                    }
                },
            )
            val state by vm.state.collectAsStateWithLifecycle()
            ThreadScreen(
                state = state,
                clock = clock,
                timestampOf = vm::gutterTimestamp,
                onEvent = vm::onEvent,
                onBack = { navController.popBackStack() },
                onLinkClick = { url ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                },
            )
        }

        composable(Routes.NEW_THREAD) {
            val vm: NewThreadViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { NewThreadViewModel(container.threadRepository, container.settings) }
                },
            )
            val state by vm.state.collectAsStateWithLifecycle()
            val connState by container.connectionManager.state.collectAsState()
            LaunchedEffect(vm) {
                vm.created.collect { threadId ->
                    navController.navigate(Routes.thread(threadId)) {
                        popUpTo(Routes.THREADS)
                    }
                }
            }
            NewThreadScreen(
                state = state,
                clock = clock,
                connState = connState,
                onEvent = vm::onEvent,
                onCancel = { navController.popBackStack() },
            )
        }

        composable(Routes.CONFIG) {
            val vm: ConfigViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ConfigViewModel(
                            settingsStore = container.settings,
                            apiKeyStore = container.apiKeyStore,
                            connectionManager = container.connectionManager,
                            repo = container.threadRepository,
                            allowHttp = BuildConfig.DEBUG,
                        )
                    }
                },
            )
            val state by vm.state.collectAsStateWithLifecycle()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            ConfigScreen(
                state = state,
                clock = clock,
                tabs = tabs,
                appVersion = BuildConfig.VERSION_NAME,
                onEvent = vm::onEvent,
                onRevealRequested = {
                    if (state.settings.biometricUnlock) {
                        onRevealKey { vm.onEvent(net.liquidx.leman.ui.config.ConfigEvent.RevealKey) }
                    } else {
                        vm.onEvent(net.liquidx.leman.ui.config.ConfigEvent.RevealKey)
                    }
                },
                onExport = {
                    scope.launch {
                        onShareExport(vm.exportJson())
                    }
                },
                onOpenThreads = { navController.navigate(Routes.THREADS) { launchSingleTop = true } },
                onSelectTab = { tab -> navController.navigate(tab.id) { launchSingleTop = true } },
            )
        }

        container.debugHooks?.registerDestinations(this, navController)
    }
}
