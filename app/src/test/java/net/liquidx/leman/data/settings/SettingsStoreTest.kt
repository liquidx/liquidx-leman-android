package net.liquidx.leman.data.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.domain.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private fun newStore(scope: kotlinx.coroutines.CoroutineScope): SettingsStore {
        val dir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "test-${System.nanoTime()}",
        )
        return SettingsStore(scope) { File(dir, "settings.preferences_pb") }
    }

    @Test
    fun settings_defaults_matchSpecTable() = runTest {
        val store = newStore(this)
        val s = store.settings.first()
        assertEquals(Settings(), s)
        assertEquals("https://api.gent.ino.ink", s.serverUrl)
        assertEquals("juno", s.agentName)
        assertEquals("✳", s.agentGlyph)
        assertEquals(false, s.biometricUnlock)
        assertEquals(false, s.expandTracesByDefault)
        assertEquals(true, s.showToolArgs)
    }

    @Test
    fun update_persistsAllFields() = runTest {
        val store = newStore(this)
        store.update {
            it.copy(
                serverUrl = "https://gw.example",
                agentName = "ariel",
                agentGlyph = "⌬",
                biometricUnlock = true,
                expandTracesByDefault = true,
                showToolArgs = false,
            )
        }
        val s = store.settings.first()
        assertEquals("https://gw.example", s.serverUrl)
        assertEquals("ariel", s.agentName)
        assertEquals("⌬", s.agentGlyph)
        assertEquals(true, s.biometricUnlock)
        assertEquals(true, s.expandTracesByDefault)
        assertEquals(false, s.showToolArgs)
    }
}
