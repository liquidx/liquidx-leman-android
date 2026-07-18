package net.liquidx.leman.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.liquidx.leman.domain.model.Settings

/** DataStore-backed user settings (spec 03 table). */
class SettingsStore(
    scope: CoroutineScope,
    produceFile: () -> File,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope,
        { File(context.filesDir, "datastore/settings.preferences_pb") },
    )

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val agentName = stringPreferencesKey("agent_name")
        val agentGlyph = stringPreferencesKey("agent_glyph")
        val biometricUnlock = booleanPreferencesKey("biometric_unlock")
        val expandTraces = booleanPreferencesKey("expand_traces_by_default")
        val showToolArgs = booleanPreferencesKey("show_tool_args")
        val notificationsEnabled = booleanPreferencesKey("notifications_enabled")
    }

    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile)

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            serverUrl = p[Keys.serverUrl] ?: Settings.DEFAULT_SERVER_URL,
            agentName = p[Keys.agentName] ?: Settings.DEFAULT_AGENT_NAME,
            agentGlyph = p[Keys.agentGlyph] ?: Settings.DEFAULT_AGENT_GLYPH,
            biometricUnlock = p[Keys.biometricUnlock] ?: false,
            expandTracesByDefault = p[Keys.expandTraces] ?: false,
            showToolArgs = p[Keys.showToolArgs] ?: true,
            notificationsEnabled = p[Keys.notificationsEnabled] ?: false,
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { p ->
            val current = Settings(
                serverUrl = p[Keys.serverUrl] ?: Settings.DEFAULT_SERVER_URL,
                agentName = p[Keys.agentName] ?: Settings.DEFAULT_AGENT_NAME,
                agentGlyph = p[Keys.agentGlyph] ?: Settings.DEFAULT_AGENT_GLYPH,
                biometricUnlock = p[Keys.biometricUnlock] ?: false,
                expandTracesByDefault = p[Keys.expandTraces] ?: false,
                showToolArgs = p[Keys.showToolArgs] ?: true,
                notificationsEnabled = p[Keys.notificationsEnabled] ?: false,
            )
            val next = transform(current)
            p[Keys.serverUrl] = next.serverUrl
            p[Keys.agentName] = next.agentName
            p[Keys.agentGlyph] = next.agentGlyph
            p[Keys.biometricUnlock] = next.biometricUnlock
            p[Keys.expandTraces] = next.expandTracesByDefault
            p[Keys.showToolArgs] = next.showToolArgs
            p[Keys.notificationsEnabled] = next.notificationsEnabled
        }
    }

    suspend fun reset() {
        store.edit { it.clear() }
    }
}
