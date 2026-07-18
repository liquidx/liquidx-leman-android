package net.liquidx.leman.data.settings

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.cancel
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

    @Test
    fun values_persistAcrossStoreInstances() = runTest {
        // Create a shared directory so both stores point to the same file
        val sharedDir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "push-test-shared-${System.nanoTime()}",
        )
        val sharedFile = { File(sharedDir, "push.preferences_pb") }

        // Create first store in its own scope and set values
        val scope1 = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val store1 = PushPrefsStore(scope1, sharedFile)
        val deviceId1 = store1.deviceId()
        store1.markSeeded()

        // Cancel the first store's scope to release the file lock
        scope1.cancel()

        // Create second store pointing to the same file in the test scope
        val store2 = PushPrefsStore(this, sharedFile)

        // Verify the second store sees the same persisted values
        assertEquals(deviceId1, store2.deviceId())
        assertTrue(store2.hasSeeded())
    }
}
