package net.liquidx.leman.messaging

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.liquidx.leman.LemanApp
import net.liquidx.leman.data.repo.SyncChange
import net.liquidx.leman.domain.model.ApiError
import net.liquidx.leman.domain.model.ApiResult

/**
 * Wakes on an FCM push, reconciles Room from the gateway (reusing SessionSyncer),
 * and posts a notification per new agent reply. Posts only when already seeded (so
 * the first-ever populate can't flood) and the app isn't foreground.
 */
class SyncNotifyWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as LemanApp).container
        if (!container.settings.settings.first().notificationsEnabled) return Result.success()

        // A throw here (e.g. a Keystore decrypt failure reading the api key) would
        // otherwise fail the worker permanently; a later attempt may well succeed.
        val wasSeeded: Boolean
        val result = try {
            container.configurePushClient()
            wasSeeded = container.pushPrefs.hasSeeded()
            container.threadRepository.syncForNotifications()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return Result.retry()
        }

        return when (result) {
            is ApiResult.Ok -> {
                container.pushPrefs.markSeeded()
                if (!isForeground()) {
                    container.messageNotifier.post(PushDecision.toPost(wasSeeded, result.value))
                }
                Result.success()
            }
            is ApiResult.Err -> when (result.error) {
                is ApiError.Network, ApiError.Timeout, is ApiError.Server -> Result.retry()
                else -> Result.success()
            }
        }
    }

    private suspend fun isForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}

/** Pure seed gate (unit-tested). Duplicate/retry pushes are deduped by the syncer's persisted serverLastActive. */
object PushDecision {
    fun toPost(wasSeeded: Boolean, changes: List<SyncChange>): List<SyncChange> =
        if (wasSeeded) changes else emptyList()
}
