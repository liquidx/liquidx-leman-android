package net.liquidx.leman.messaging

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.liquidx.leman.MainActivity
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
        SyncChange(id, "Title $id", "preview $id")

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
    fun post_multipleChanges_addsGroupSummary() {
        MessageNotifier(context, permissionGranted = { true })
            .post(listOf(change("a"), change("b")))

        val summaries = nm.activeNotifications.filter {
            (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
        }
        assertEquals("expected exactly one group summary", 1, summaries.size)
        assertEquals(
            "2 new messages",
            summaries.single().notification.extras.getString("android.title"),
        )
        // summary + one per thread; the summary id must not collide with a thread id
        assertEquals(3, nm.activeNotifications.size)
    }

    @Test
    fun post_singleChange_hasNoGroupSummary() {
        MessageNotifier(context, permissionGranted = { true }).post(listOf(change("a")))
        assertEquals(1, nm.activeNotifications.size)
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

    @Test
    fun post_granted_notificationTapDeeplinkToThread() {
        val notifier = MessageNotifier(context, permissionGranted = { true })
        notifier.post(listOf(change("t1")))

        val statusBarNotification = nm.activeNotifications.find {
            it.notification.extras.getString("android.title") == "Title t1"
        }
        assertTrue("Expected notification for t1", statusBarNotification != null)

        val contentIntent = statusBarNotification!!.notification.contentIntent
        val shadowPI = shadowOf(contentIntent)
        val intent = shadowPI.savedIntent

        assertEquals("Deep-link URI should be leman://thread/t1", "leman://thread/t1", intent.data.toString())
        assertEquals("Target should be MainActivity", MainActivity::class.java.name, intent.component?.className)
    }
}
