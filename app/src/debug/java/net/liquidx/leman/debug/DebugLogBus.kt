package net.liquidx.leman.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NetLogEntry(
    val id: Long,
    val method: String,
    val path: String,
    val status: Int,
    val durationMs: Long,
    val requestBytes: Long,
    val responseBytes: Long,
    val requestBody: String?,
    val responseBody: String?,
    val curl: String,
)

data class EventLogEntry(
    val id: Long,
    val name: String,
    val timestamp: Double,
    val payload: String,
)

/** In-memory ring buffers behind NETWORK LOG / EVENT CONSOLE (spec 08). */
class DebugLogBus {
    private var nextId = 0L

    private val _netLog = MutableStateFlow<List<NetLogEntry>>(emptyList())
    val netLog: StateFlow<List<NetLogEntry>> = _netLog.asStateFlow()

    private val _events = MutableStateFlow<List<EventLogEntry>>(emptyList())
    val events: StateFlow<List<EventLogEntry>> = _events.asStateFlow()

    fun logNet(entry: (Long) -> NetLogEntry) {
        _netLog.update { (it + entry(nextId++)).takeLast(RING_SIZE) }
    }

    fun logEvent(name: String, timestamp: Double, payload: String) {
        _events.update { (it + EventLogEntry(nextId++, name, timestamp, payload)).takeLast(RING_SIZE) }
    }

    fun clear() {
        _netLog.value = emptyList()
        _events.value = emptyList()
    }

    private companion object {
        const val RING_SIZE = 200
    }
}
