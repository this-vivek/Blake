package com.blake.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.URLEncoder

/** Steps and photo frames for one exercise or warm-up move (assets/exercise_guide.json, built
 * by tools/build_exercise_guide.py). */
data class ExerciseGuideEntry(
    val name: String,
    val steps: List<String>,
    val frames: List<String>,
    val source: String,
)

object ExerciseGuide {
    @Volatile
    private var cache: Map<String, ExerciseGuideEntry>? = null

    private fun load(context: Context): Map<String, ExerciseGuideEntry> {
        cache?.let { return it }
        val parsed = try {
            val text = context.assets.open("exercise_guide.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            root.keys().asSequence().associateWith { key ->
                val o = root.getJSONObject(key)
                fun list(name: String) =
                    o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                ExerciseGuideEntry(o.optString("name", key), list("steps"), list("frames"), o.optString("source"))
            }
        } catch (e: Exception) {
            emptyMap()
        }
        cache = parsed
        return parsed
    }

    fun get(context: Context, name: String): ExerciseGuideEntry? = load(context)[name.lowercase()]

    /** There is no free video library covering every move, so "Watch a demo" opens a YouTube
     * search for it (the YouTube app if installed, otherwise the browser). */
    fun openDemo(context: Context, name: String) {
        val query = URLEncoder.encode("$name exercise proper form", "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // no browser or YouTube app available: nothing else to try
        }
    }
}
