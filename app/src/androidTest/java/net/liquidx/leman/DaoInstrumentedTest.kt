package net.liquidx.leman

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.liquidx.leman.data.local.LemanDatabase
import net.liquidx.leman.data.local.ThreadEntity
import net.liquidx.leman.data.local.TurnEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** On-device Room checks (spec 07). Deeper coverage lives in the JVM DaoTest. */
@RunWith(AndroidJUnit4::class)
class DaoInstrumentedTest {

    private lateinit var db: LemanDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LemanDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun threadAndTurns_roundTripOnDevice() = runBlocking {
        db.threadDao().upsertThread(
            ThreadEntity(
                id = "a", title = "t", preview = "p", state = "idle", pinned = false,
                unread = false, createdAt = 1, lastActiveAt = 2, source = "api_server",
                agentName = null, agentGlyph = null,
            ),
        )
        db.turnDao().upsertTurns(
            listOf(
                TurnEntity(
                    id = "t1", threadId = "a", seq = 1, kind = "user", createdAt = 1,
                    markdown = "hello", blocksJson = null, traceJson = null, runId = null,
                    sendState = "synced", viaButton = false,
                ),
            ),
        )
        assertEquals(1, db.threadDao().observeThreads().first().size)
        assertEquals("hello", db.turnDao().observeTurns("a").first().single().markdown)
    }
}
