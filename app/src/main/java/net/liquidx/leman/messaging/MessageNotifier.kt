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
    private val permissionGranted: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    },
) {
    fun post(changes: List<SyncChange>) {
        if (changes.isEmpty() || !permissionGranted()) return
        ensureChannel()
        val nm = NotificationManagerCompat.from(context)
        for (c in changes) nm.notify(c.threadId.hashCode(), build(c))
        if (changes.size > 1) nm.notify(SUMMARY_ID, buildSummary(changes.size))
    }

    private fun build(c: SyncChange): android.app.Notification {
        val intent = Intent(Intent.ACTION_VIEW, "leman://thread/${c.threadId}".toUri())
            .setClass(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context,
            c.threadId.hashCode(),
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
        private const val SUMMARY_ID = -1
    }
}
