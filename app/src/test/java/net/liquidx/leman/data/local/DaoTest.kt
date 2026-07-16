package net.liquidx.leman.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest {

    private lateinit var db: LemanDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LemanDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun thread(id: String, pinned: Boolean = false, lastActiveAt: Long = 0) = ThreadEntity(
        id = id, title = "t-$id", preview = "p", state = "idle", pinned = pinned,
        unread = false, createdAt = 0, lastActiveAt = lastActiveAt,
        source = "api_server", agentName = null, agentGlyph = null,
    )

    private fun turn(id: String, threadId: String, seq: Long) = TurnEntity(
        id = id, threadId = threadId, seq = seq, kind = "user", createdAt = 0,
        markdown = "m", blocksJson = null, traceJson = null, runId = null,
        sendState = "synced", viaButton = false,
    )

    @Test
    fun observeThreads_ordersPinnedFirstThenByLastActive() = runTest {
        db.threadDao().upsertThread(thread("a", pinned = false, lastActiveAt = 30))
        db.threadDao().upsertThread(thread("b", pinned = true, lastActiveAt = 10))
        db.threadDao().upsertThread(thread("c", pinned = false, lastActiveAt = 20))
        val ids = db.threadDao().observeThreads().first().map { it.id }
        assertEquals(listOf("b", "a", "c"), ids)
    }

    @Test
    fun observeTurns_ordersBySeq() = runTest {
        db.threadDao().upsertThread(thread("a"))
        db.turnDao().upsertTurns(listOf(turn("t2", "a", 2), turn("t1", "a", 1), turn("t3", "a", 3)))
        val seqs = db.turnDao().observeTurns("a").first().map { it.seq }
        assertEquals(listOf(1L, 2L, 3L), seqs)
    }

    @Test
    fun maxSeq_emptyThread_isNull() = runTest {
        db.threadDao().upsertThread(thread("a"))
        assertNull(db.turnDao().maxSeq("a"))
    }

    @Test
    fun maxSeq_returnsHighest() = runTest {
        db.threadDao().upsertThread(thread("a"))
        db.turnDao().upsertTurns(listOf(turn("t1", "a", 1), turn("t2", "a", 7)))
        assertEquals(7L, db.turnDao().maxSeq("a"))
    }

    @Test
    fun upsertThread_replacesExistingRow() = runTest {
        db.threadDao().upsertThread(thread("a"))
        db.threadDao().upsertThread(thread("a").copy(title = "renamed"))
        assertEquals("renamed", db.threadDao().observeThreads().first().single().title)
    }

    @Test
    fun deleteThread_cascadesToTurns() = runTest {
        db.threadDao().upsertThread(thread("a"))
        db.turnDao().upsertTurns(listOf(turn("t1", "a", 1)))
        db.threadDao().deleteThread("a")
        assertEquals(0, db.turnDao().observeTurns("a").first().size)
    }

    @Test
    fun clearAll_wipesBothTables() = runTest {
        db.threadDao().upsertThread(thread("a"))
        db.turnDao().upsertTurns(listOf(turn("t1", "a", 1)))
        db.threadDao().clearAllThreads()
        assertEquals(0, db.threadDao().observeThreads().first().size)
        assertEquals(0, db.turnDao().observeTurns("a").first().size)
    }

    @Test
    fun deleteTurnsFor_removesOnlyThatThreadsTurns() = runTest {
        db.threadDao().upsertThread(thread("a"))
        db.threadDao().upsertThread(thread("b"))
        db.turnDao().upsertTurns(listOf(turn("t1", "a", 1)))
        db.turnDao().upsertTurns(listOf(turn("t2", "b", 1)))

        db.turnDao().deleteTurnsFor("a")

        assertEquals(0, db.turnDao().getTurns("a").size)
        assertEquals(1, db.turnDao().getTurns("b").size)
    }
}
