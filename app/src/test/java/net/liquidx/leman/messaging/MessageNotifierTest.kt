package net.liquidx.leman.messaging

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.liquidx.leman.data.repo.SyncChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MessageNotifierTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val nm get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    private fun change(id: String) =
        SyncChange(id, "Title $id", "preview $id", isNewSession = false, serverLastActive = 1L)

    @Test
    fun post_granted_showsPerChange_andCreatesChannel() {
        val notifier = MessageNotifier(context, permissionGranted = { true })
        notifier.post(listOf(change("a"), change("b")))
        assertTrue(
            nm.notificationChannels.any {
                (it as android.app.NotificationChannel).id == MessageNotifier.CHANNEL_ID
            },
        )
        val titles = nm.activeNotifications.map { it.notification.extras.getString("android.title") }
        assertTrue(titles.contains("Title a"))
        assertTrue(titles.contains("Title b"))
    }

    @Test
    fun post_denied_showsNothing() {
        MessageNotifier(context, permissionGranted = { false }).post(listOf(change("a")))
        assertEquals(0, nm.activeNotifications.size)
    }

    @Test
    fun post_empty_noop() {
        MessageNotifier(context, permissionGranted = { true }).post(emptyList())
        assertEquals(0, nm.activeNotifications.size)
    }
}
