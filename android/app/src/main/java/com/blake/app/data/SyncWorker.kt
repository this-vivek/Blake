package com.blake.app.data

import android.content.Context
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blake.app.BlakeApi
import com.blake.app.Prefs
import com.blake.app.WidgetRefresh
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
        var anyDelivered = false
        for (item in pending) {
            val result = if (item.kind == "chat") sendChat(api, item) else {
                api.rawRequest(item.method, item.path, item.bodyJson)
            }
            result.fold(
                onSuccess = { json ->
                    reconcile(db, item, json)
                    db.outboxDao().delete(item)
                    anyDelivered = true
                },
                onFailure = {
                    anyFailed = true
                    db.outboxDao().update(
                        item.copy(attempts = item.attempts + 1, lastError = it.message)
                    )
                }
            )
        }
        if (anyDelivered) {
            // The widget reads the saved /today, so refresh that now that the writes landed.
            try {
                api.today().getOrNull()?.let { fresh ->
                    db.todayDao().upsert(TodayCacheEntity(json = fresh, updatedAt = System.currentTimeMillis()))
                }
            } catch (e: Exception) {
                // offline again or server busy: the widget keeps its last numbers
            }
            WidgetRefresh.update(applicationContext)
        }
        return if (anyFailed) Result.retry() else Result.success()
    }

    /** /chat is multipart (text + optional image), not the JSON rawRequest() sends, so its
     * queued body (built by OutboxRepository.enqueueChat) base64-embeds the image instead. */
    private fun sendChat(api: BlakeApi, item: OutboxEntity): kotlin.Result<String> {
        val body = JSONObject(item.bodyJson)
        val imageBytes = if (body.has("image_base64")) {
            Base64.decode(body.getString("image_base64"), Base64.NO_WRAP)
        } else null
        return api.chat(
            text = body.optString("text"),
            imageBytes = imageBytes,
            mimeType = body.optString("mime_type", "image/jpeg"),
            clientId = body.optString("client_id"),
        )
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
