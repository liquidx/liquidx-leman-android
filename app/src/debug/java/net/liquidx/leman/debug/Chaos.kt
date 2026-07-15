package net.liquidx.leman.debug

import kotlinx.coroutines.flow.MutableStateFlow

/** Fault-injection flags (spec 08 CHAOS). */
data class ChaosFlags(
    val extraLatencyMs: Long = 0,          // 0 / 500 / 2000
    val failNextRestCalls: Int = 0,        // synthetic 500s
    val corruptNextPayload: Boolean = false,
    val dropStream: Boolean = false,       // kill the run event stream mid-flight
    val clockSkewHours: Long = 0,          // +25h exercises TODAY/YESTERDAY bucketing
)

class ChaosState {
    val flags = MutableStateFlow(ChaosFlags())

    fun update(transform: (ChaosFlags) -> ChaosFlags) {
        flags.value = transform(flags.value)
    }

    /** Consumes one fail-next token; true = this call should fail. */
    fun consumeFailToken(): Boolean {
        val current = flags.value
        if (current.failNextRestCalls <= 0) return false
        flags.value = current.copy(failNextRestCalls = current.failNextRestCalls - 1)
        return true
    }

    fun consumeCorruptToken(): Boolean {
        val current = flags.value
        if (!current.corruptNextPayload) return false
        flags.value = current.copy(corruptNextPayload = false)
        return true
    }
}
