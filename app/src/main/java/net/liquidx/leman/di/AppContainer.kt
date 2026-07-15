package net.liquidx.leman.di

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.liquidx.leman.BuildConfig
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.OkHttpHermesClient
import net.liquidx.leman.data.repo.ConnectionManager
import net.liquidx.leman.data.repo.ThreadRepository
import net.liquidx.leman.data.settings.ApiKeyStore
import net.liquidx.leman.data.settings.KeystoreApiKeyStore
import net.liquidx.leman.data.settings.SettingsStore
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
    )

    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val debugHooks: DebugHooks? by lazy {
        overrides.debugHooks ?: if (BuildConfig.DEBUG) loadDebugHooks() else null
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext, appScope) }

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
        )
    }

    private fun loadDebugHooks(): DebugHooks? = runCatching {
        Class.forName("net.liquidx.leman.debug.DebugHooksImpl")
            .getDeclaredConstructor()
            .newInstance() as DebugHooks
    }.getOrNull()
}
