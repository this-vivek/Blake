package com.blake.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cached copy of GET /today's full JSON payload. Single row (id=0) — the UI reads this via
 * a Flow and renders it immediately on screen launch, before any network call completes. */
@Entity(tableName = "today_cache")
data class TodayCacheEntity(
    @PrimaryKey val id: Int = 0,
    val json: String,
    val updatedAt: Long,
)

/** Cached copy of GET /goals. Same singleton-row pattern as [TodayCacheEntity]. */
@Entity(tableName = "goals_cache")
data class GoalsCacheEntity(
    @PrimaryKey val id: Int = 0,
    val json: String,
    val updatedAt: Long,
)

/** One row per event, mirroring GET /history's shape — lets Timeline filter/group locally
 * without a network round-trip. Negative ids mark optimistic rows not yet confirmed by the
 * server (see OutboxEntity) — replaced with the real id once the matching write syncs. */
@Entity(tableName = "cached_events", primaryKeys = ["id", "module"])
data class CachedEventEntity(
    val id: Long,
    val module: String,
    val kind: String,
    val ts: Double,
    val payloadJson: String,
    val pending: Boolean = false,
)

/** A write the app couldn't (or didn't wait to) send yet. Inserted the instant a user logs
 * something; a WorkManager job (see SyncWorker) drains this with backoff. clientId is the
 * same idempotency key the backend already supports (P0.3) — a retried sync is a no-op
 * server-side, never a duplicate. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: String,
    val path: String,
    val method: String,
    val bodyJson: String,
    val createdAt: Long,
    val kind: String,
    val attempts: Int = 0,
    val lastError: String? = null,
)
