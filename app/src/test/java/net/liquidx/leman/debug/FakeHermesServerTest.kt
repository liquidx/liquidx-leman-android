package net.liquidx.leman.debug

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import net.liquidx.leman.data.remote.RunStatus
import net.liquidx.leman.data.remote.WireMessage
import net.liquidx.leman.domain.model.ApiResult
import net.liquidx.leman.domain.model.RunEvent
import net.liquidx.leman.domain.model.getOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeHermesServerTest {

    @Test
    fun demoScenario_acceptsRun_emitsDeltasThenCompleted() = runTest {
        val fake = FakeHermesServer()
        fake.scenario.value = FakeScenario.Demo
        val accepted = fake.startRun(listOf(WireMessage("user", "do the thing")), null)
        val runId = (accepted as ApiResult.Ok).value.runId

        val events = fake.runEvents(runId).toList()
        assertTrue(events.any { it is RunEvent.MessageDelta })
        assertTrue(events.last() is RunEvent.RunCompleted)

        val polled = fake.getRun(runId).getOrNull()!!
        assertEquals(RunStatus.Completed, polled.runStatus)
        assertEquals((events.last() as RunEvent.RunCompleted).output, polled.output)
    }

    @Test
    fun finishedRun_replaysFullHistoryOnReopen() = runTest {
        val fake = FakeHermesServer()
        fake.scenario.value = FakeScenario.Demo
        val runId = (fake.startRun(listOf(WireMessage("user", "x")), null) as ApiResult.Ok).value.runId
        val first = fake.runEvents(runId).toList()
        val second = fake.runEvents(runId).toList()
        assertEquals(first.size, second.size) // replay, like the real gateway
    }

    @Test
    fun hostileScenario_dropsFirstStream_thenCompletesOnSecond() = runTest {
        val fake = FakeHermesServer()
        fake.scenario.value = FakeScenario.Hostile
        val runId = (fake.startRun(listOf(WireMessage("user", "x")), null) as ApiResult.Ok).value.runId

        val firstAttempt = runCatching { fake.runEvents(runId).toList() }
        assertTrue(firstAttempt.isFailure)

        val second = fake.runEvents(runId).toList()
        assertTrue(second.last() is RunEvent.RunCompleted)
        assertEquals(RunStatus.Completed, fake.getRun(runId).getOrNull()!!.runStatus)
    }

    @Test
    fun unknownRun_pollReturns404Client() = runTest {
        val fake = FakeHermesServer()
        assertTrue(fake.getRun("nope") is ApiResult.Err)
    }
}
