package net.liquidx.leman.data.repo

import kotlin.random.Random

/** Exponential backoff 1s → 30s cap, ±20% jitter, reset on success (spec 04). */
class Backoff(
    private val baseMillis: Long = 1_000,
    private val capMillis: Long = 30_000,
    private val jitterFraction: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    fun nextDelayMillis(): Long {
        val exp = (baseMillis shl attempt.coerceAtMost(20)).coerceAtMost(capMillis)
        attempt++
        val jitter = 1.0 + (random.nextDouble() * 2 - 1) * jitterFraction
        return (exp * jitter).toLong().coerceAtLeast(0)
    }

    fun reset() {
        attempt = 0
    }
}
