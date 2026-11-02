package com.blake.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TodayDao {
    @Query("SELECT * FROM today_cache WHERE id = 0")
    fun observe(): Flow<TodayCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TodayCacheEntity)
}

@Dao
interface GoalsDao {
    @Query("SELECT * FROM goals_cache WHERE id = 0")
    fun observe(): Flow<GoalsCacheEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GoalsCacheEntity)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM cached_events WHERE module = :module ORDER BY ts DESC LIMIT :limit")
    fun observeByModule(module: String, limit: Int = 100): Flow<List<CachedEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: CachedEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<CachedEventEntity>)

    @Query("DELETE FROM cached_events WHERE id = :tempId AND module = :module")
    suspend fun deleteById(tempId: Long, module: String)
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entity: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Update
    suspend fun update(entity: OutboxEntity)

    @Delete
    suspend fun delete(entity: OutboxEntity)
}
