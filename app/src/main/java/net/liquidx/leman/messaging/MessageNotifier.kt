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
        val posted = changes.mapTo(mutableSetOf()) { notificationId(it.threadId) }
        for (c in changes) nm.notify(notificationId(c.threadId), build(c))
        // Union with what was already showing: a push carrying one change while
        // another thread's notification is still up leaves two in the shade, and
        // sizing the summary off this batch alone would miss it entirely.
        reconcileSummary(activeThreadIds() + posted)
    }

    /**
     * Clears a thread's notification once it's actually been read in-app —
     * `setAutoCancel(true)` alone only clears it on tap, so it otherwise lingers
     * in the shade after the user reads the thread by any other route.
     */
    fun cancel(threadId: String) {
        val id = notificationId(threadId)
        context.getSystemService(NotificationManager::class.java).cancel(id)
        reconcileSummary(activeThreadIds() - id)
    }

    /**
     * Keeps the group summary honest about how many threads are actually showing.
     * Android only surfaces a summary once a group has two or more children, and a
     * summary posted earlier keeps its old text forever unless re-posted — so a
     * count is re-derived from the live set on every post and cancel.
     *
     * The caller passes the expected set rather than trusting [activeThreadIds]
     * alone: NotificationManagerService applies posts and cancels asynchronously,
     * so a notify/cancel issued moments earlier may not be visible yet.
     */
    private fun reconcileSummary(threadIds: Set<Int>) {
        val nm = NotificationManagerCompat.from(context)
        if (threadIds.size >= 2) {
            nm.notify(SUMMARY_ID, buildSummary(threadIds.size))
        } else {
            nm.cancel(SUMMARY_ID)
        }
    }

    /** Ids of this group's currently-showing per-thread notifications (summary excluded). */
    private fun activeThreadIds(): Set<Int> =
        context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .filter { it.id != SUMMARY_ID && it.notification.group == GROUP }
            .mapTo(mutableSetOf()) { it.id }

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
