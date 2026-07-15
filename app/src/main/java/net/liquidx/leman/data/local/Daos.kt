package net.liquidx.leman.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadDao {
    @Query("SELECT * FROM threads ORDER BY pinned DESC, lastActiveAt DESC")
    fun observeThreads(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE id = :id")
    suspend fun getThread(id: String): ThreadEntity?

    @Upsert
    suspend fun upsertThread(t: ThreadEntity)

    @Query("DELETE FROM threads WHERE id = :id")
    suspend fun deleteThread(id: String)

    @Query("DELETE FROM threads")
    suspend fun clearAllThreads()

    @Query("SELECT COUNT(*) FROM threads")
    suspend fun threadCount(): Int
}

@Dao
interface TurnDao {
    @Query("SELECT * FROM turns WHERE threadId = :id ORDER BY seq")
    fun observeTurns(id: String): Flow<List<TurnEntity>>

    @Query("SELECT * FROM turns WHERE threadId = :id ORDER BY seq")
    suspend fun getTurns(id: String): List<TurnEntity>

    @Query("SELECT * FROM turns WHERE id = :id")
    suspend fun getTurn(id: String): TurnEntity?

    @Query("SELECT MAX(seq) FROM turns WHERE threadId = :id")
    suspend fun maxSeq(id: String): Long?

    @Upsert
    suspend fun upsertTurn(t: TurnEntity)

    @Upsert
    suspend fun upsertTurns(items: List<TurnEntity>)

    @Query("DELETE FROM turns WHERE id = :id")
    suspend fun deleteTurn(id: String)

    @Query("SELECT COUNT(*) FROM turns")
    suspend fun turnCount(): Int

    @Query("SELECT COUNT(*) FROM turns WHERE sendState != 'synced'")
    suspend fun unsyncedCount(): Int
}
