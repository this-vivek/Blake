package com.blake.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TodayCacheEntity::class,
        GoalsCacheEntity::class,
        CachedEventEntity::class,
        OutboxEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class BlakeDatabase : RoomDatabase() {
    abstract fun todayDao(): TodayDao
    abstract fun goalsDao(): GoalsDao
    abstract fun eventDao(): EventDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var instance: BlakeDatabase? = null

        fun get(context: Context): BlakeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BlakeDatabase::class.java,
                    "blake_local.db", // distinct from the backend's own "blake.db" (different machine entirely, but avoids any naming confusion when reading code)
                ).build().also { instance = it }
            }
    }
}
