package net.liquidx.leman.di

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import net.liquidx.leman.BuildConfig
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.OkHttpHermesClient
import net.liquidx.leman.data.repo.ConnectionManager
import net.liquidx.leman.data.repo.SyncScheduler
import net.liquidx.leman.data.repo.ThreadRepository
import net.liquidx.leman.data.settings.ApiKeyStore
import net.liquidx.leman.data.settings.KeystoreApiKeyStore
import net.liquidx.leman.data.settings.PushPrefsStore
import net.liquidx.leman.data.settings.SettingsStore
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.messaging.DeviceRegistrar
import net.liquidx.leman.messaging.MessageNotifier
import net.liquidx.leman.messaging.fetchFcmToken
import net.liquidx.leman.ui.components.LemanTab

/**
 * Debug-only extension surface (spec 08): the debug source set provides an
 * implementation via reflection; release builds compile none of it.
 */
interface DebugHooks {
    val extraTabs: List<LemanTab> get() = emptyList()
    fun interceptors(): List<okhttp3.Interceptor> = emptyList()
    fun attach(container: AppContainer) {}

    /** Lets debug builds swap in the in-process fake gateway at runtime (spec 08). */
    fun wrapClient(real: HermesClient): HermesClient = real
    fun registerDestinations(builder: NavGraphBuilder, navController: NavController) {}
}

/** Manual DI graph (spec 01): ~10 objects, lazy singletons, no Hilt/Koin. */
class AppContainer(
    context: Context,
    private val overrides: Overrides = Overrides(),
) {
    data class Overrides(
        val hermesClient: HermesClient? = null,
        val db: LemanDatabase? = null,
        val apiKeyStore: ApiKeyStore? = null,
        val debugHooks: DebugHooks? = null,
        // DataStore requires a process-wide singleton per file. Instrumented tests run
        // in the live app process alongside LemanApp's container, and each test builds
        // its own container — so tests inject an isolated, per-test SettingsStore here
        // rather than colliding on the production settings file.
        val settings: SettingsStore? = null,
        val pushPrefs: PushPrefsStore? = null,
    )

    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val debugHooks: DebugHooks? by lazy {
        overrides.debugHooks ?: if (BuildConfig.DEBUG) loadDebugHooks() else null
    }

    val settings: SettingsStore by lazy { overrides.settings ?: SettingsStore(appContext, appScope) }

    val pushPrefs: PushPrefsStore by lazy { overrides.pushPrefs ?: PushPrefsStore(appContext, appScope) }

    val messageNotifier: MessageNotifier by lazy { MessageNotifier(appContext) }

    val deviceRegistrar: DeviceRegistrar by lazy {
        DeviceRegistrar(
            client = hermesClient,
            settingsStore = settings,
            apiKeyStore = apiKeyStore,
            pushPrefs = pushPrefs,
            tokenProvider = { fetchFcmToken() },
        )
    }

    /**
     * An FCM-launched process never runs ConnectionManager.reconfigure(), so a
     * background worker must configure the client transport itself before any call.
     */
    suspend fun configurePushClient() {
        hermesClient.reconfigure(settings.settings.first().serverUrl, apiKeyStore.get())
    }

    val apiKeyStore: ApiKeyStore by lazy {
        overrides.apiKeyStore ?: KeystoreApiKeyStore(appContext, appScope)
    }

    val db: LemanDatabase by lazy { overrides.db ?: LemanDatabase.build(appContext) }

    val hermesClient: HermesClient by lazy {
        overrides.hermesClient ?: run {
            val real = OkHttpHermesClient(
                userAgent = "leman-android/${BuildConfig.VERSION_NAME}",
                interceptors = debugHooks?.interceptors().orEmpty(),
            )
            debugHooks?.wrapClient(real) ?: real
        }
    }

    val connectionManager: ConnectionManager by lazy {
        ConnectionManager(
            client = hermesClient,
            settings = settings.settings,
            apiKey = { apiKeyStore.get() },
            scope = appScope,
        )
    }

    val threadRepository: ThreadRepository by lazy {
        ThreadRepository(
            db = db,
            client = hermesClient,
            scope = appScope,
            onAuthFailure = { code -> connectionManager.onAuthFailure(code) },
            pushPrefs = pushPrefs,
            // messageNotifier is captured by reference (it's `by lazy`), so this is
            // safe even though threadRepository is constructed first in the graph —
            // the lambda only resolves messageNotifier when markRead actually fires.
            onThreadRead = { threadId -> messageNotifier.cancel(threadId) },
        )
    }

    val syncScheduler: SyncScheduler by lazy {
        SyncScheduler(
            syncNow = {
                val result = threadRepository.syncNow()
                if (result is ApiResult.Ok && !pushPrefs.hasSeeded()) pushPrefs.markSeeded()
            },
            connState = connectionManager.state,
            scope = appScope,
        )
    }

    private fun loadDebugHooks(): DebugHooks? = runCatching {
        Class.forName("net.liquidx.leman.debug.DebugHooksImpl")
            .getDeclaredConstructor()
            .newInstance() as DebugHooks
    }.getOrNull()
}
