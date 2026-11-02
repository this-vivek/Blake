package com.blake.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Talks to the Blake backend's /chat endpoint. Server may need ~30s to cold-start on free hosting. */
class BlakeApi(private val baseUrl: String, private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(40, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** @param mimeType the image's real content type (e.g. from ContentResolver.getType) —
     * mislabeling a PNG/HEIC as "image/jpeg" gets the request rejected by Claude's API. Only
     * image/jpeg, image/png, image/gif, image/webp are actually supported upstream.
     * @param clientId lets a retried send (e.g. from the offline outbox) be a no-op server-side
     * instead of re-running Claude's tool calls and double-logging. */
    fun chat(text: String, imageBytes: ByteArray?, mimeType: String = "image/jpeg", clientId: String? = null): Result<String> {
        return try {
            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("text", text)
            if (clientId != null) {
                bodyBuilder.addFormDataPart("client_id", clientId)
            }
            if (imageBytes != null) {
                bodyBuilder.addFormDataPart(
                    "image", "photo",
                    imageBytes.toRequestBody(mimeType.toMediaType())
                )
            }
            val request = Request.Builder()
                .url("$baseUrl/chat")
                .header("X-API-Key", apiKey)
                .post(bodyBuilder.build())
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(body)
                else Result.failure(IOException("HTTP ${resp.code}: $body"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    fun summary(): Result<String> = get("$baseUrl/summary")

    fun today(): Result<String> = get("$baseUrl/today")

    /** Fire-and-forget on app foreground — starts the free-tier host's cold boot while
     * cached data is already on screen, so the next real request doesn't eat that latency. */
    fun health(): Result<String> = get("$baseUrl/health")

    fun history(module: String, limit: Int = 20): Result<String> =
        get("$baseUrl/history?module=$module&limit=$limit")

    fun suggestWorkout(location: String, muscleGroup: String): Result<String> =
        get("$baseUrl/suggest/workout?location=${enc(location)}&muscle_group=${enc(muscleGroup)}")

    fun suggestNextSet(exercise: String): Result<String> =
        get("$baseUrl/suggest/next_set?exercise=${enc(exercise)}")

    fun suggestMeal(meal: String): Result<String> =
        get("$baseUrl/suggest/meal?meal=${enc(meal)}")

    fun suggestMealRefresh(meal: String): Result<String> =
        get("$baseUrl/suggest/meal/refresh?meal=${enc(meal)}")

    fun getGoals(): Result<String> = get("$baseUrl/goals")

    fun putGoals(
        kcal: Double? = null,
        proteinG: Double? = null,
        dailySpend: Double? = null,
        workoutDaysPerWeek: Int? = null,
        bodyweightKg: Double? = null,
    ): Result<String> {
        val body = JSONObject().apply {
            kcal?.let { put("kcal", it) }
            proteinG?.let { put("protein_g", it) }
            dailySpend?.let { put("daily_spend", it) }
            workoutDaysPerWeek?.let { put("workout_days_per_week", it) }
            bodyweightKg?.let { put("bodyweight_kg", it) }
        }.toString()
        return try {
            val request = Request.Builder()
                .url("$baseUrl/goals")
                .header("X-API-Key", apiKey)
                .put(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(respBody)
                else Result.failure(IOException("HTTP ${resp.code}: $respBody"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    fun completeTask(taskId: Int): Result<String> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/tasks/$taskId/complete")
                .header("X-API-Key", apiKey)
                .post("".toRequestBody(null))
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(body)
                else Result.failure(IOException("HTTP ${resp.code}: $body"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    fun voidEvent(eventId: Int): Result<String> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/events/$eventId/void")
                .header("X-API-Key", apiKey)
                .post("".toRequestBody(null))
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(body)
                else Result.failure(IOException("HTTP ${resp.code}: $body"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** Generic POST/PUT by path+body — used by the outbox sync worker, which stores a queued
     * write as (path, method, bodyJson) rather than typed per-endpoint. */
    fun rawRequest(method: String, path: String, bodyJson: String): Result<String> {
        return try {
            val requestBody = bodyJson.toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder().url("$baseUrl$path").header("X-API-Key", apiKey)
            when (method.uppercase()) {
                "POST" -> requestBuilder.post(requestBody)
                "PUT" -> requestBuilder.put(requestBody)
                else -> return Result.failure(IllegalArgumentException("unsupported method $method"))
            }
            client.newCall(requestBuilder.build()).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(respBody)
                else Result.failure(IOException("HTTP ${resp.code}: $respBody"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): Result<String> {
        return try {
            val request = Request.Builder().url(url).header("X-API-Key", apiKey).build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(body)
                else Result.failure(IOException("HTTP ${resp.code}: $body"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }
}
