package com.blake.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/** Opens YouTube Music (optionally on the playlist link from Settings) when a session
 * starts, then sends a "play" media key. There is no API to start playback inside another
 * app, so this is best effort: the key goes to whichever media app was active most recently,
 * so it starts YouTube Music only if that was the last player you used. */
object MusicLauncher {
    const val PACKAGE = "com.google.android.apps.youtube.music"
    const val KEY_URL = "music_url"
    const val KEY_ASK = "music_ask"

    private const val PLAY_DELAY_MS = 2_500L

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
                requestPlay(context.applicationContext)
                return true
            } catch (e: ActivityNotFoundException) {
                // try the next option
            }
        }
        return false
    }

    /** Gives the app a moment to come up, then presses "play" (not play/pause, so it can never
     * pause something that already started on its own). */
    private fun requestPlay(appContext: Context) {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        Handler(Looper.getMainLooper()).postDelayed({
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        }, PLAY_DELAY_MS)
    }
}
