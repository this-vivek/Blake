package com.blake.app.data

import android.content.Context
import android.util.Base64
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

    /** /chat takes multipart form data (text + an optional image), not JSON, so it can't go
     * through [enqueue]'s rawRequest path — the image is instead base64-embedded in bodyJson
     * and SyncWorker decodes it back out before calling api.chat(). */
    suspend fun enqueueChat(context: Context, text: String, imageBytes: ByteArray?, mimeType: String?): Long {
        val db = BlakeDatabase.get(context)
        val clientId = UUID.randomUUID().toString()
        val body = JSONObject().put("text", text).put("client_id", clientId)
        if (imageBytes != null) {
            body.put("image_base64", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
            body.put("mime_type", mimeType ?: "image/jpeg")
        }
        val entity = OutboxEntity(
            clientId = clientId,
            path = "/chat",
            method = "POST",
            bodyJson = body.toString(),
            createdAt = System.currentTimeMillis(),
            kind = "chat",
        )
        val id = db.outboxDao().insert(entity)
        SyncWorker.enqueue(context)
        return id
    }
}
