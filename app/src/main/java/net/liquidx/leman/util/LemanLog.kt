package net.liquidx.leman.util

import android.util.Log
import net.liquidx.leman.BuildConfig

/**
 * Thin feature-tagged logger (spec 08): no-op in release; debug builds tee
 * into the DebugLogBus via [sink] so the debug panel renders the same stream
 * as logcat. Never log the API key — callers mask before logging.
 */
object LemanLog {
    const val NET = "leman.net"
    const val SSE = "leman.sse"
    const val SYNC = "leman.sync"
    const val DB = "leman.db"
    const val MD = "leman.md"

    var sink: ((tag: String, message: String) -> Unit)? = null

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
            sink?.invoke(tag, message)
        }
    }

    /** `hm_…3kf2`-style masking for anything that might carry the bearer token. */
    fun maskKey(value: String): String =
        if (value.length <= 7) "••••" else value.take(3) + "…" + value.takeLast(4)
}
