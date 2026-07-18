package net.liquidx.leman.data.settings

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushPrefsStoreTest {

    private fun newStore(scope: kotlinx.coroutines.CoroutineScope): PushPrefsStore {
        val dir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "push-test-${System.nanoTime()}",
        )
        return PushPrefsStore(scope) { File(dir, "push.preferences_pb") }
    }

    @Test
    fun deviceId_isStableAcrossReads() = runTest {
        val store = newStore(this)
        val first = store.deviceId()
        assertTrue(first.isNotBlank())
        assertEquals(first, store.deviceId())
    }

    @Test
    fun seeded_defaultsFalse_thenSticks() = runTest {
        val store = newStore(this)
        assertFalse(store.hasSeeded())
        store.markSeeded()
        assertTrue(store.hasSeeded())
    }
}
