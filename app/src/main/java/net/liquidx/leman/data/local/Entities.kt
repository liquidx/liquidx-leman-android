package net.liquidx.leman.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room is a cache of the gateway's session store; `id` IS the server session id (spec 03). */
@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey val id: String,          // server session id
    val title: String,                   // derived from first user message
    val preview: String,                 // last turn snippet, maintained on write
    val state: String,                   // idle | running | failed (client-owned)
    val pinned: Boolean,
    val unread: Boolean,                 // set when a run completes off-screen
    val createdAt: Long,
    val lastActiveAt: Long,              // orders the list; bumped on every turn
    val source: String,                  // api_server | cron | cli — server-owned (spec 03)
    val agentName: String?,
    val agentGlyph: String?,             // per-thread identity override
    // Server's last_active (ms) as of the last sync — the syncer's change-detection
    // key. Distinct from lastActiveAt, which the app also bumps from its local clock
    // on send/finalize; comparing those to the server value spuriously rebuilds
    // app-touched threads every tick. Entity-only; not surfaced to the domain.
    val serverLastActive: Long = 0,
)

@Entity(
    tableName = "turns",
    indices = [Index("threadId", "seq")],
    foreignKeys = [
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TurnEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val seq: Long,                       // client-assigned, monotonic within thread
    val kind: String,                    // user | agent | trace
    val createdAt: Long,
    val markdown: String?,               // user text / agent output
    val blocksJson: String?,             // structured agent blocks if any (spec 05)
    val traceJson: String?,              // finalized trace, one JSON column
    val runId: String?,                  // server run that produced an agent/trace turn
    val sendState: String,               // synced | sending | failed (user turns)
    val viaButton: Boolean,
)
