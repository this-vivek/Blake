package com.blake.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blake.app.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Drains the outbox whenever network is available. WorkManager's own network constraint
 * means this never even attempts a request while offline — it just waits — and its backoff
 * policy spaces out retries on failure, so a queued write syncs exactly once on reconnect. */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = BlakeDatabase.get(applicationContext)
        val api = Prefs.apiFor(applicationContext)
        val pending = db.outboxDao().getAllPending()
        if (pending.isEmpty()) return Result.success()

        var anyFailed = false
        for (item in pending) {
            val result = api.rawRequest(item.method, item.path, item.bodyJson)
            result.fold(
                onSuccess = { json ->
                    reconcile(db, item, json)
                    db.outboxDao().delete(item)
                },
                onFailure = {
                    anyFailed = true
                    db.outboxDao().update(
                        item.copy(attempts = item.attempts + 1, lastError = it.message)
                    )
                }
            )
        }
        return if (anyFailed) Result.retry() else Result.success()
    }

    /** Replace the optimistic (negative, temp-id) cached_events row with one carrying the
     * server-confirmed id, once the matching write actually lands. The log-endpoint response
     * shape (e.g. {"logged": ..., "total": ..., "event_id": ...}) differs from the event
     * payload shape /history returns (e.g. {"meal": ..., "items": ..., "total": ...}) that
     * formatEntryText expects, so this rebuilds the latter from the response + original
     * request body rather than storing the raw response. */
    private suspend fun reconcile(db: BlakeDatabase, item: OutboxEntity, responseJson: String) {
        val module = when (item.kind) {
            "meal" -> "meals"
            "set" -> "workouts"
            else -> return
        }
        val tempId = -item.id
        db.eventDao().deleteById(tempId, module)

        val resp = JSONObject(responseJson)
        if (!resp.has("event_id")) return
        val reqBody = JSONObject(item.bodyJson)

        val payload = when (item.kind) {
            "meal" -> JSONObject()
                .put("meal", reqBody.optString("meal"))
                .put("items", resp.optJSONArray("logged") ?: JSONArray())
                .put("total", resp.optJSONObject("total") ?: JSONObject())
            else -> {
                val logged = resp.optJSONObject("logged") ?: JSONObject()
                JSONObject()
                    .put("exercise", logged.optString("exercise"))
                    .put("weight_kg", logged.optDouble("weight_kg"))
                    .put("reps", logged.optInt("reps"))
                    .put("e1rm", resp.optDouble("e1rm"))
                    .put("kcal_burned", resp.optDouble("kcal_burned"))
            }
        }

        val confirmed = CachedEventEntity(
            id = resp.optLong("event_id"),
            module = module,
            kind = if (item.kind == "meal") "meal" else "set",
            ts = System.currentTimeMillis() / 1000.0,
            payloadJson = payload.toString(),
            pending = false,
        )
        db.eventDao().upsert(confirmed)
    }

    companion object {
        private const val UNIQUE_NAME = "blake-outbox-sync"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
