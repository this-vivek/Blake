package com.blake.app.data

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Queues a write for background delivery, embedding a client-generated UUID into the body
 * so a retried/duplicated sync is a no-op server-side (reuses the backend's existing
 * client_id idempotency support, P0.3) rather than ever double-logging. */
object OutboxRepository {
    suspend fun enqueue(context: Context, path: String, method: String, bodyJson: String, kind: String): Long {
        val db = BlakeDatabase.get(context)
        val clientId = UUID.randomUUID().toString()
        val obj = JSONObject(bodyJson)
        obj.put("client_id", clientId)
        val entity = OutboxEntity(
            clientId = clientId,
            path = path,
            method = method,
            bodyJson = obj.toString(),
            createdAt = System.currentTimeMillis(),
            kind = kind,
        )
        val id = db.outboxDao().insert(entity)
        SyncWorker.enqueue(context)
        return id
    }
}
