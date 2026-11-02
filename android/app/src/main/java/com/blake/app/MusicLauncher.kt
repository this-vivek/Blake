package com.blake.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens YouTube Music (optionally on the playlist link from Settings) when a session
 * starts. There is no public API to start playback inside another app, so this only brings
 * the app or playlist up; a watch-style playlist link usually starts playing by itself. */
object MusicLauncher {
    const val PACKAGE = "com.google.android.apps.youtube.music"
    const val KEY_URL = "music_url"
    const val KEY_ASK = "music_ask"

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    fun url(context: Context): String = prefs(context).getString(KEY_URL, "")?.trim() ?: ""

    fun saveUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_URL, value.trim()).apply()
    }

    fun askBeforeSession(context: Context): Boolean = prefs(context).getBoolean(KEY_ASK, true)

    fun setAskBeforeSession(context: Context, ask: Boolean) {
        prefs(context).edit().putBoolean(KEY_ASK, ask).apply()
    }

    /** True if some app was opened. */
    fun open(context: Context): Boolean {
        val link = url(context)
        val candidates = mutableListOf<Intent>()
        if (link.isNotEmpty()) {
            candidates += Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PACKAGE)
        }
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { candidates += it }
        candidates += Intent(Intent.ACTION_VIEW, Uri.parse(link.ifEmpty { "https://music.youtube.com" }))
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                // try the next option
            }
        }
        return false
    }
}
