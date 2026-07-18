package net.liquidx.leman.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * Internal state for the push subsystem — NOT user-facing settings. Kept in its
 * own DataStore file so notification bookkeeping never mixes with [SettingsStore].
 */
class PushPrefsStore(
    scope: CoroutineScope,
    produceFile: () -> File,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope,
        { File(context.filesDir, "datastore/push.preferences_pb") },
    )

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val hasSeeded = booleanPreferencesKey("has_seeded_sync")
    }

    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = produceFile)

    /** A stable per-install id, generated once so a rotated token replaces (not duplicates) this device. */
    suspend fun deviceId(): String {
        store.data.first()[Keys.deviceId]?.let { return it }
        val fresh = UUID.randomUUID().toString()
        var result = fresh
        store.edit { p -> result = p[Keys.deviceId] ?: fresh.also { p[Keys.deviceId] = it } }
        return result
    }

    suspend fun hasSeeded(): Boolean = store.data.first()[Keys.hasSeeded] ?: false

    suspend fun markSeeded() {
        store.edit { it[Keys.hasSeeded] = true }
    }
}
