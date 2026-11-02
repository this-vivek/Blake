package com.blake.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The latest published build: CI publishes every passing build of main as the "latest"
 * GitHub release (see .github/workflows/android.yml). */
data class UpdateInfo(val sha: String, val title: String, val publishedAt: String, val downloadUrl: String?)

sealed class UpdateResult {
    data class UpToDate(val sha: String) : UpdateResult()
    data class Available(val info: UpdateInfo) : UpdateResult()
    data class Unknown(val reason: String) : UpdateResult()
}

object UpdateCheck {
    private const val URL = "https://api.github.com/repos/this-vivek/Blake/releases/tags/latest"
    private const val KEY_LAST_CHECK = "update_last_check"
    private const val KEY_DISMISSED = "update_dismissed_sha"
    private const val MIN_HOURS_BETWEEN_AUTO_CHECKS = 12L

    /** Set when a newer build exists; the Today screen shows a card for it. */
    var available by mutableStateOf<UpdateInfo?>(null)

    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    private fun prefs(c: Context) = c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    /** The commit this APK was built from (the part after "+" in the version), null for local builds. */
    fun installedSha(context: Context): String? = try {
        val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        name.substringAfter('+', "").takeIf { it.isNotBlank() && it != "local" }
    } catch (e: Exception) {
        null
    }

    private fun fetchLatest(): Result<UpdateInfo> = try {
        val request = Request.Builder().url(URL).header("Accept", "application/vnd.github+json").build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            when {
                resp.code == 404 -> Result.failure(IOException("No build has been published yet"))
                !resp.isSuccessful -> Result.failure(IOException("GitHub answered ${resp.code}"))
                else -> {
                    val o = JSONObject(body)
                    val fromNotes = Regex("Commit ([0-9a-f]{7,40})").find(o.optString("body"))?.groupValues?.get(1)
                    val fromTitle = Regex("([0-9a-f]{7})\\s*$").find(o.optString("name"))?.groupValues?.get(1)
                    val sha = (fromNotes ?: fromTitle)?.take(7)
                    val assets = o.optJSONArray("assets")
                    val url = if (assets != null && assets.length() > 0) assets.getJSONObject(0).optString("browser_download_url") else null
                    if (sha == null) Result.failure(IOException("The release has no commit in it"))
                    else Result.success(UpdateInfo(sha, o.optString("name"), o.optString("published_at"), url?.ifBlank { null }))
                }
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Checks GitHub (blocking: call from a background thread). The automatic check on launch
     * runs at most every 12 hours; the Settings button passes force = true. */
    fun check(context: Context, force: Boolean): UpdateResult {
        val installed = installedSha(context) ?: return UpdateResult.Unknown("This is a local build, so there is nothing to compare.")
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0L) < TimeUnit.HOURS.toMillis(MIN_HOURS_BETWEEN_AUTO_CHECKS)) {
            return UpdateResult.Unknown("Checked recently")
        }
        val latest = fetchLatest().getOrElse { return UpdateResult.Unknown(it.message ?: "Couldn't check") }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        return if (latest.sha == installed) {
            available = null
            UpdateResult.UpToDate(installed)
        } else {
            if (prefs.getString(KEY_DISMISSED, null) != latest.sha || force) available = latest
            UpdateResult.Available(latest)
        }
    }

    fun dismiss(context: Context, info: UpdateInfo) {
        prefs(context).edit().putString(KEY_DISMISSED, info.sha).apply()
        available = null
    }

    /** Opens the APK download in the browser; the phone installs it from there. */
    fun openDownload(context: Context, info: UpdateInfo) {
        val url = info.downloadUrl ?: "https://github.com/this-vivek/Blake/releases/tag/latest"
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: android.content.ActivityNotFoundException) {
            // no browser installed
        }
    }
}
