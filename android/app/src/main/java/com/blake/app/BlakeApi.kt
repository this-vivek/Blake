package com.blake.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Talks to the Blake backend's /chat endpoint. Server may need ~30s to cold-start on free hosting. */
class BlakeApi(private val baseUrl: String, private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(40, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    /** @param imageBytes optional photo; jpeg assumed. */
    fun chat(text: String, imageBytes: ByteArray?): Result<String> {
        return try {
            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("text", text)
            if (imageBytes != null) {
                bodyBuilder.addFormDataPart(
                    "image", "photo.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaType())
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
