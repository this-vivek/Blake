package com.blake.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
     * image/jpeg, image/png, image/gif, image/webp are actually supported upstream. */
    fun chat(text: String, imageBytes: ByteArray?, mimeType: String = "image/jpeg"): Result<String> {
        return try {
            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("text", text)
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

    fun history(module: String, limit: Int = 20): Result<String> =
        get("$baseUrl/history?module=$module&limit=$limit")

    fun suggestWorkout(location: String, muscleGroup: String): Result<String> =
        get("$baseUrl/suggest/workout?location=${enc(location)}&muscle_group=${enc(muscleGroup)}")

    fun suggestMeal(meal: String): Result<String> =
        get("$baseUrl/suggest/meal?meal=${enc(meal)}")

    fun logMeal(meal: String, items: List<Pair<String, Double>>): Result<String> {
        val itemsJson = JSONArray()
        items.forEach { (name, grams) ->
            itemsJson.put(JSONObject().put("name", name).put("grams", grams))
        }
        val body = JSONObject().put("meal", meal).put("items", itemsJson).toString()
        return try {
            val request = Request.Builder()
                .url("$baseUrl/log/meal")
                .header("X-API-Key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
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

    fun getGoals(): Result<String> = get("$baseUrl/goals")

    fun putGoals(
        kcal: Double? = null,
        proteinG: Double? = null,
        dailySpend: Double? = null,
        workoutDaysPerWeek: Int? = null,
    ): Result<String> {
        val body = JSONObject().apply {
            kcal?.let { put("kcal", it) }
            proteinG?.let { put("protein_g", it) }
            dailySpend?.let { put("daily_spend", it) }
            workoutDaysPerWeek?.let { put("workout_days_per_week", it) }
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
