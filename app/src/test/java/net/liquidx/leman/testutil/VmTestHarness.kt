package net.liquidx.leman.testutil

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.repo.Backoff
import net.liquidx.leman.data.repo.ConnectionManager
import net.liquidx.leman.data.repo.ThreadRepository
import net.liquidx.leman.data.settings.SettingsStore
import net.liquidx.leman.domain.model.Settings
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Swaps Dispatchers.Main for a test dispatcher (ViewModel scopes). */
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * Everything a ViewModel test needs, all running on the test scheduler:
 * in-memory Room, fake client, real repository/ConnectionManager/SettingsStore.
 */
class VmHarness(testScope: TestScope) {
    val dispatcher = StandardTestDispatcher(testScope.testScheduler)
    val scope = CoroutineScope(dispatcher)
    val client = FakeHermesClient()
    var now: Long = FIXED_NOW

    val db: LemanDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        LemanDatabase::class.java,
    ).setQueryCoroutineContext(dispatcher).build()

    private var idCounter = 0
    val repo = ThreadRepository(
        db = db,
        client = client,
        scope = scope,
        clock = { now },
        newId = { "id-${idCounter++}" },
        backoffFactory = { Backoff(random = Random(1)) },
    )

    val settingsStore = SettingsStore(scope) {
        File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "vmtest-${System.nanoTime()}/settings.preferences_pb",
        )
    }

    val apiKeyStore = FakeApiKeyStore()

    val settingsFlow: Flow<Settings> get() = settingsStore.settings

    val connectionManager = ConnectionManager(
        client = client,
        settings = settingsFlow,
        apiKey = { apiKeyStore.get() },
        scope = scope,
        backoff = Backoff(random = Random(1)),
    )

    fun close() = db.close()

    companion object {
        /** 2026-07-15T12:00:00Z — tests pin the zone to UTC. */
        val FIXED_NOW: Long = java.time.LocalDateTime.of(2026, 7, 15, 12, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }
}
