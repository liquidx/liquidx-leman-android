package net.liquidx.leman.messaging

import net.liquidx.leman.data.repo.SyncChange
import org.junit.Assert.assertEquals
import org.junit.Test

class PushDecisionTest {
    private val changes = listOf(
        SyncChange("a", "A", "p"),
    )
    @Test fun notSeeded_suppresses() = assertEquals(emptyList<SyncChange>(), PushDecision.toPost(false, changes))
    @Test fun seeded_passesThrough() = assertEquals(changes, PushDecision.toPost(true, changes))
}
