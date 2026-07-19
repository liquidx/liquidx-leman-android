package net.liquidx.leman.messaging

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import net.liquidx.leman.MainActivity
import net.liquidx.leman.R
import net.liquidx.leman.data.repo.SyncChange

/**
 * Posts one local notification per new agent reply. Tapping deep-links to the
 * thread via `leman://thread/{id}` (declared on the THREAD destination). No-ops
 * without POST_NOTIFICATIONS. Channel is created lazily and idempotently.
 */
class MessageNotifier(
    private val context: Context,
    // The runtime grant alone isn't enough: the user can leave POST_NOTIFICATIONS
    // granted and still switch the app (or its channel) off in system settings, in
    // which case nothing is ever delivered. Both must hold.
    private val permissionGranted: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    },
) {
    fun post(changes: List<SyncChange>) {
        if (changes.isEmpty() || !permissionGranted()) return
        ensureChannel()
        val nm = NotificationManagerCompat.from(context)
        for (c in changes) nm.notify(notificationId(c.threadId), build(c))
        if (changes.size > 1) nm.notify(SUMMARY_ID, buildSummary(changes.size))
    }

    /**
     * Clears a thread's notification once it's actually been read in-app —
     * `setAutoCancel(true)` alone only clears it on tap, so it otherwise lingers
     * in the shade after the user reads the thread by any other route. Also
     * clears the group summary once it's the only one left (getActiveNotifications()
     * is available at our minSdk, so checking is cheap and race-free enough here —
     * worst case a stale summary lingers one post() cycle, never a wrong cancel).
     */
    fun cancel(threadId: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(notificationId(threadId))
        val anyThreadNotificationsLeft = nm.activeNotifications.any {
            it.id != SUMMARY_ID && it.notification.group == GROUP
        }
        if (!anyThreadNotificationsLeft) nm.cancel(SUMMARY_ID)
    }

    // Single source of truth for the id derivation so post/cancel can never drift.
    private fun notificationId(threadId: String) = threadId.hashCode()

    private fun build(c: SyncChange): android.app.Notification {
        val intent = Intent(Intent.ACTION_VIEW, "leman://thread/${c.threadId}".toUri())
            .setClass(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            notificationId(c.threadId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(c.title)
            .setContentText(c.preview)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setContentIntent(pi)
            .build()
    }

    private fun buildSummary(count: Int): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("$count new messages")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "New messages", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        const val CHANNEL_ID = "new_messages"
        private const val GROUP = "net.liquidx.leman.NEW_MESSAGES"
        // Per-thread ids are threadId.hashCode(); MIN_VALUE is the one Int no
        // String hash can produce, so the summary can never clobber a thread.
        private const val SUMMARY_ID = Int.MIN_VALUE
    }
}
