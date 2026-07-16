package net.liquidx.leman.data.repo

import androidx.room.withTransaction
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.data.remote.HermesClient
import net.liquidx.leman.data.remote.SessionDto
import net.liquidx.leman.domain.model.ApiResult

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
    suspend fun syncOnce(): ApiResult<Unit> {
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
            val lastMarkdown = turns.lastOrNull { it.markdown != null }?.markdown
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
            // One transaction: observers never see a thread whose turns were deleted
            // but not yet rebuilt. Unsynced local turns (a sending/failed user message
            // the server hasn't accepted) are carried across the rebuild so the retry
            // affordance survives — appended past the rebuilt max seq.
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
        return ApiResult.Ok(Unit)
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
