package net.liquidx.leman.data.repo

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun nextDelay_followsDoublingSequenceCappedAt30s_withinJitterBounds() {
        val backoff = Backoff(random = Random(42))
        val expectedBases = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        for (base in expectedBases) {
            val delay = backoff.nextDelayMillis()
            assertTrue(
                "delay $delay out of ±20% of $base",
                delay >= (base * 0.8).toLong() && delay <= (base * 1.2).toLong(),
            )
        }
    }

    @Test
    fun reset_returnsToBaseDelay() {
        val backoff = Backoff(random = Random(1))
        repeat(4) { backoff.nextDelayMillis() }
        backoff.reset()
        val delay = backoff.nextDelayMillis()
        assertTrue(delay in 800..1200)
    }

    @Test
    fun jitter_isDeterministicForSeededRandom() {
        val a = Backoff(random = Random(7))
        val b = Backoff(random = Random(7))
        assertEquals(a.nextDelayMillis(), b.nextDelayMillis())
    }
}
