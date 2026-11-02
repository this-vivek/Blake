package com.blake.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blake.app.data.BlakeDatabase
import com.blake.app.data.CachedEventEntity
import com.blake.app.data.OutboxRepository
import com.blake.app.data.TodayCacheEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Queues a meal exactly the way the Today screen does: instantly visible, delivered once. */
object QuickLog {
    suspend fun logUsual(context: Context, slot: String, items: JSONArray) {
        val body = JSONObject().put("meal", slot).put("items", items).toString()
        val outboxId = OutboxRepository.enqueue(context, "/log/meal", "POST", body, "meal")
        BlakeDatabase.get(context).eventDao().upsert(
            CachedEventEntity(
                id = -outboxId,
                module = "meals",
                kind = "meal",
                ts = System.currentTimeMillis() / 1000.0,
                payloadJson = JSONObject().put("meal", slot).put("items", items).toString(),
                pending = true,
            )
        )
    }
}

/**
 * Local meal reminders. A worker wakes about every 30 minutes (WorkManager is inexact, so no
 * exact-alarm permission is needed) and posts a notification for a meal only if its time has
 * come and it is still unlogged. At most [MAX_PER_DAY] a day, each meal once a day.
 */
object Reminders {
    // A new id: a channel's importance can't be raised once created, so the old quiet
    // "meal_reminders" channel is left behind and this louder one replaces it.
    const val CHANNEL_ID = "blake_alerts"
    private const val OLD_CHANNEL_ID = "meal_reminders"
    private const val ALERT_COLOR = 0xFFE6A04A.toInt()
    private val VIBRATION = longArrayOf(0, 250, 120, 250, 120, 400)
    private const val WORK_NAME = "blake-reminders"
    const val MAX_PER_DAY = 3
    val SLOTS = listOf("breakfast", "lunch", "dinner")

    private val DEFAULT_MINUTES = mapOf("breakfast" to 8 * 60 + 30, "lunch" to 13 * 60, "dinner" to 20 * 60)
    private const val WINDOW_MINUTES = 120  // a reminder is only useful shortly after its time

    private fun prefs(c: Context) = c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("reminders_enabled", false)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("reminders_enabled", on).apply()
        if (on) schedule(c) else WorkManager.getInstance(c).cancelUniqueWork(WORK_NAME)
    }

    fun scheduleIfEnabled(c: Context) {
        if (enabled(c)) schedule(c)
    }

    private fun schedule(c: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(c)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun override(c: Context, slot: String): Int? =
        prefs(c).getInt("reminder_time_$slot", -1).takeIf { it >= 0 }

    fun setOverride(c: Context, slot: String, minutes: Int?) {
        prefs(c).edit().apply {
            if (minutes == null) remove("reminder_time_$slot") else putInt("reminder_time_$slot", minutes)
        }.apply()
    }

    /** Your override, else when you usually log this meal, else a sensible default. */
    fun effectiveMinutes(c: Context, slot: String, usualHour: Double?): Int =
        override(c, slot) ?: usualHour?.let { (it * 60).toInt() } ?: DEFAULT_MINUTES.getValue(slot)

    fun clockText(minutes: Int): String {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60) }
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
    }

    private fun todayKey() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun sentToday(c: Context): Int = prefs(c).getInt("reminder_count_${todayKey()}", 0)
    fun alreadySent(c: Context, slot: String): Boolean = prefs(c).getBoolean("reminder_sent_${todayKey()}_$slot", false)

    fun markSent(c: Context, slot: String) {
        val key = todayKey()
        prefs(c).edit()
            .putBoolean("reminder_sent_${key}_$slot", true)
            .putInt("reminder_count_$key", sentToday(c) + 1)
            .apply()
    }

    /** When you usually eat each meal, from the server's last /today (null if unknown). */
    suspend fun usualHours(c: Context): Map<String, Double?> {
        val cached = BlakeDatabase.get(c).todayDao().observe().first() ?: return emptyMap()
        return usualHoursFrom(JSONObject(cached.json))
    }

    fun usualHoursFrom(today: JSONObject): Map<String, Double?> {
        val usual = today.optJSONObject("nutrition")?.optJSONObject("usual") ?: return emptyMap()
        return SLOTS.associateWith { slot ->
            usual.optJSONObject(slot)?.let { if (it.has("usual_hour") && !it.isNull("usual_hour")) it.optDouble("usual_hour") else null }
        }
    }

    fun canNotify(c: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(c).areNotificationsEnabled()

    fun notificationId(slot: String) = 2000 + SLOTS.indexOf(slot)


    /** Heads-up channel: high importance (pops over the screen), vibration and lights. */
    private fun ensureChannel(c: Context) {
        val manager = NotificationManagerCompat.from(c)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Blake alerts")
                .setDescription("Meal and task reminders")
                .setVibrationEnabled(true)
                .setVibrationPattern(VIBRATION)
                .setLightsEnabled(true)
                .setLightColor(ALERT_COLOR)
                .build()
        )
    }

    private fun alertBuilder(c: Context, title: String, text: String, open: PendingIntent): NotificationCompat.Builder =
        NotificationCompat.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_blake)
            .setColor(ALERT_COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text).setBigContentTitle(title))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setVibrate(VIBRATION)
            .setLights(ALERT_COLOR, 600, 600)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)

    /** One notification for tasks due today or overdue. */
    fun postTasks(c: Context, titles: List<String>) {
        if (!canNotify(c)) return
        ensureChannel(c)
        val open = PendingIntent.getActivity(
            c, 2100, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (titles.size == 1) "Task due: ${titles[0]}" else "${titles.size} tasks need you today"
        val notification = alertBuilder(c, title, titles.joinToString("\n") { "\u2022 $it" }, open)
            .addAction(0, "Open tasks", open)
            .build()
        try {
            NotificationManagerCompat.from(c).notify(2100, notification)
        } catch (e: SecurityException) {
            // permission revoked between the check and the call
        }
    }

    const val TASKS_SLOT = "tasks"
    const val TASKS_HOUR = 9

    fun post(c: Context, slot: String, usual: JSONObject?) {
        if (!canNotify(c)) return
        ensureChannel(c)
        val id = notificationId(slot)
        val open = PendingIntent.getActivity(
            c, id, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val items = usual?.optJSONArray("items")
        val hasUsual = items != null && items.length() > 0 && ((usual?.optInt("times_logged", 0) ?: 0) > 0)
        val names = if (hasUsual) (0 until items!!.length()).joinToString(", ") { items.getJSONObject(it).optString("name") } else ""
        val kcal = usual?.optJSONObject("total")?.optDouble("kcal", 0.0) ?: 0.0

        val slotName = slot.replaceFirstChar { it.uppercase() }
        val builder = alertBuilder(
            c,
            "$slotName is still not logged",
            if (hasUsual) "Your usual: $names · ${kcal.toInt()} kcal. One tap to log it." else "Tap to log it before you forget.",
            open,
        )
        if (hasUsual) {
            val log = PendingIntent.getBroadcast(
                c, id,
                Intent(c, LogUsualReceiver::class.java)
                    .putExtra("slot", slot).putExtra("items", items.toString()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Log usual", log)
            builder.addAction(0, "Edit", open)
        }
        try {
            NotificationManagerCompat.from(c).notify(id, builder.build())
        } catch (e: SecurityException) {
            // permission revoked between the check and the call: nothing to do
        }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!Reminders.enabled(c)) return Result.success()
        val today = freshOrCachedToday(c) ?: return Result.success()

        val nutrition = today.optJSONObject("nutrition")
        val logged = mutableSetOf<String>()
        nutrition?.optJSONArray("meal_slots_logged")?.let { arr -> for (i in 0 until arr.length()) logged += arr.getString(i) }
        // Meals logged on this phone but not synced yet count too.
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000.0
        BlakeDatabase.get(c).eventDao().observeByModule("meals").first()
            .filter { it.pending && it.ts >= dayStart }
            .forEach { logged += JSONObject(it.payloadJson).optString("meal") }

        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val usualHours = Reminders.usualHoursFrom(today)
        val usualBySlot = nutrition?.optJSONObject("usual")

        for (slot in Reminders.SLOTS) {
            if (Reminders.sentToday(c) >= Reminders.MAX_PER_DAY) break
            if (slot in logged || Reminders.alreadySent(c, slot)) continue
            val target = Reminders.effectiveMinutes(c, slot, usualHours[slot])
            if (minutes in target..(target + 120)) {
                Reminders.post(c, slot, usualBySlot?.optJSONObject(slot))
                Reminders.markSent(c, slot)
            }
        }

        // Tasks due today or overdue: one reminder a day from 9:00, counted in the daily limit.
        if (minutes >= Reminders.TASKS_HOUR * 60 && Reminders.sentToday(c) < Reminders.MAX_PER_DAY &&
            !Reminders.alreadySent(c, Reminders.TASKS_SLOT)
        ) {
            val tasks = today.optJSONArray("open_tasks")
            val due = (0 until (tasks?.length() ?: 0)).map { tasks!!.getJSONObject(it) }
                .filter { !it.isNull("due_status") && it.optString("due_status") in setOf("today", "overdue") }
                .map { it.optString("title") }
            if (due.isNotEmpty()) {
                Reminders.postTasks(c, due)
                Reminders.markSent(c, Reminders.TASKS_SLOT)
            }
        }
        return Result.success()
    }

    /** Best effort refresh so a meal logged on another device doesn't trigger a reminder;
     * falls back to the last copy the app saved. */
    private suspend fun freshOrCachedToday(c: Context): JSONObject? {
        val fresh = withTimeoutOrNull(10_000) { withContext(Dispatchers.IO) { Prefs.apiFor(c).today().getOrNull() } }
        if (fresh != null) {
            BlakeDatabase.get(c).todayDao().upsert(TodayCacheEntity(json = fresh, updatedAt = System.currentTimeMillis()))
            return JSONObject(fresh)
        }
        return BlakeDatabase.get(c).todayDao().observe().first()?.let { JSONObject(it.json) }
    }
}

/** The notification's "Log usual" button: queues the usual meal without opening the app. */
class LogUsualReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getStringExtra("slot") ?: return
        val items = intent.getStringExtra("items")?.let { JSONArray(it) } ?: return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                QuickLog.logUsual(app, slot, items)
                NotificationManagerCompat.from(app).cancel(Reminders.notificationId(slot))
            } finally {
                pending.finish()
            }
        }
    }
}
