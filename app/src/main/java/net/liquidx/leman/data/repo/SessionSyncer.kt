package net.liquidx.leman.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.SessionDto
import net.liquidx.leman.domain.model.ApiResult

/** A notification-worthy advance surfaced during a sync (a rebuilt thread whose newest turn is an agent reply). */
data class SyncChange(
    val threadId: String,
    val title: String,
    val preview: String,
)

/**
 * Pulls the server's session store into Room (spec 03: Room is a cache; the
 * gateway is the system of record). Local-only sidecar state — pinned, unread,
 * agentName/agentGlyph — survives every rebuild. Threads with an in-flight
 * local run are skipped and reconcile after the run completes.
 */
class SessionSyncer(
    private val db: LemanDatabase,
    private val client: HermesClient,
    private val isRunActive: (String) -> Boolean,
    private val visibleThreadId: () -> String?,
) {
    // Two callers now race: the 30s foreground SyncScheduler loop and SyncNotifyWorker
    // (FCM). The orphan reap below deletes local threads absent from *this* call's
    // serverIds snapshot, so an interleaved stale snapshot could delete a thread the
    // other call just upserted — taking its unsent turns with it. Nothing inside
    // syncOnce re-enters syncOnce, so this cannot deadlock.
    private val mutex = Mutex()

    suspend fun syncOnce(collect: ((SyncChange) -> Unit)? = null): ApiResult<Unit> = mutex.withLock {
        val sessions = mutableListOf<SessionDto>()
        var offset = 0
        while (true) {
            when (val page = client.listSessions(PAGE_SIZE, offset)) {
                is ApiResult.Err -> return ApiResult.Err(page.error)
                is ApiResult.Ok -> {
                    sessions += page.value.data
                    if (!page.value.hasMore || page.value.data.isEmpty()) break
                    offset += page.value.data.size
                }
            }
        }

        val threadDao = db.threadDao()
        val turnDao = db.turnDao()
        val serverIds = sessions.mapTo(mutableSetOf()) { it.id }
        for (local in threadDao.getThreads()) {
            if (local.id !in serverIds && !isRunActive(local.id)) threadDao.deleteThread(local.id)
        }

        for (session in sessions) {
            if (isRunActive(session.id)) continue
            val local = threadDao.getThread(session.id)
            val lastActiveMs = (session.lastActive * 1000).toLong()
            // Change-detection keys on the *server's* last_active, snapshotted at the
            // previous sync — never the app-bumped lastActiveAt, which drifts off the
            // local clock and would rebuild every app-touched thread on every tick.
            if (local != null && local.serverLastActive == lastActiveMs) continue

            val messages = when (val result = client.sessionMessages(session.id)) {
                is ApiResult.Err -> continue // partial sync is fine; next tick retries
                is ApiResult.Ok -> result.value
            }
            val turns = sessionTurns(session.id, messages)
            // Skip trailing system turns (injected framework preambles, e.g. a
            // cron/skill preamble with no reply yet) — they'd otherwise leak
            // preamble text into the thread list preview (ux-fixes spec).
            val lastMarkdown = turns.lastOrNull {
                it.markdown != null && (it.kind == "user" || it.kind == "agent")
            }?.markdown
            val firstUser = turns.firstOrNull { it.kind == "user" }?.markdown
            val rebuilt = ThreadEntity(
                id = session.id,
                title = session.title ?: (firstUser ?: session.preview.orEmpty()).snippet(80),
                preview = (lastMarkdown ?: session.preview.orEmpty()).snippet(120),
                state = "idle",
                pinned = local?.pinned ?: false,
                // brand-new rows arrive read (first sync would otherwise flood);
                // an advance on a known thread is unread unless it's on screen
                unread = local != null && visibleThreadId() != session.id,
                createdAt = (session.startedAt * 1000).toLong(),
                lastActiveAt = lastActiveMs,
                source = session.source,
                agentName = local?.agentName,
                agentGlyph = local?.agentGlyph,
                serverLastActive = lastActiveMs,
            )
            if (collect != null) {
                // Notify only when the newest conversational turn is an agent reply.
                // Exclude trace turns (which anchor before an agent's content), but NOT
                // system turns — a trailing "[IMPORTANT:" framework preamble means the
                // latest activity is an injected prompt with no reply yet, so skip it.
                val newestTurn = turns.lastOrNull { it.kind != "trace" }
                if (newestTurn?.kind == "agent") {
                    collect(
                        SyncChange(
                            threadId = session.id,
                            title = rebuilt.title,
                            preview = rebuilt.preview,
                        ),
                    )
                }
            }
            // One transaction: observers never see a thread whose turns were deleted
            // but not yet rebuilt. Unsynced local turns (a sending/failed user message
            // the server hasn't accepted) are carried across the rebuild so the retry
            // affordance survives — appended past the rebuilt max seq. (Narrow window:
            // if the server acked but this run's runId never persisted locally before a
            // crash/drop, the preserved local copy and the now-rebuilt server copy can
            // both surface as a visible duplicate — accepted trade-off.)
            db.withTransaction {
                val preserved =
                    turnDao.getTurns(session.id).filter { it.sendState != "synced" }
                threadDao.upsertThread(rebuilt)
                turnDao.deleteTurnsFor(session.id)
                turnDao.upsertTurns(turns)
                if (preserved.isNotEmpty()) {
                    var seq = turns.maxOfOrNull { it.seq } ?: 0L
                    turnDao.upsertTurns(preserved.map { it.copy(seq = ++seq) })
                }
            }
        }
        ApiResult.Ok(Unit)
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
