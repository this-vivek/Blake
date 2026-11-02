package com.blake.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.blake.app.data.BlakeDatabase
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

// Blake Design System colours (dark). Widgets cannot read the app theme.
private val SURFACE = Color(0xFF141417)
private val RAISED = Color(0xFF1C1C20)
private val INK = Color(0xFFF2F2F0)
private val MUTED = Color(0xFFA0A0A8)
private val ACCENT = Color(0xFFE6C07B)
private val ON_ACCENT = Color(0xFF16120A)
private val DANGER = Color(0xFFF08A82)
private const val TRACK = 0xFF26262B.toInt()

/** What the widget shows, taken from the last /today the app saved plus meals logged on this
 * phone that have not synced yet. */
internal data class WidgetState(
    val hasData: Boolean,
    val kcalText: String,
    val kcalUnit: String,
    val fraction: Float,
    val over: Boolean,
    val proteinText: String,
    val slot: String,
    val slotLogged: Boolean,
    val usualItems: JSONArray?,
)

internal suspend fun loadWidgetState(context: Context): WidgetState {
    val db = BlakeDatabase.get(context)
    val cached = db.todayDao().observe().first()
        ?: return WidgetState(false, "--", "open Blake", 0f, false, "", "", false, null)
    val today = JSONObject(cached.json)
    val nutrition = today.optJSONObject("nutrition")
    val kcal = nutrition?.optJSONObject("kcal")
    val protein = nutrition?.optJSONObject("protein_g")
    val actual = kcal?.optDouble("actual") ?: 0.0
    val goal = optionalDouble(kcal, "goal")
    val over = goal != null && actual > goal
    val big = when {
        goal == null -> actual.toInt()
        over -> (actual - goal).toInt()
        else -> (goal - actual).toInt()
    }
    val unit = when {
        goal == null -> "kcal eaten"
        over -> "kcal over"
        else -> "kcal left"
    }
    val proteinActual = protein?.optDouble("actual") ?: 0.0
    val proteinGoal = optionalDouble(protein, "goal")

    val slot = defaultMealForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    val logged = mutableSetOf<String>()
    nutrition?.optJSONArray("meal_slots_logged")?.let { a -> for (i in 0 until a.length()) logged += a.getString(i) }
    val dayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis / 1000.0
    db.eventDao().observeByModule("meals").first().filter { it.pending && it.ts >= dayStart }
        .forEach { logged += JSONObject(it.payloadJson).optString("meal") }

    val usual = nutrition?.optJSONObject("usual")?.optJSONObject(slot)
    val items = usual?.optJSONArray("items")?.takeIf { it.length() > 0 && usual.optInt("times_logged", 0) > 0 }

    return WidgetState(
        hasData = true,
        kcalText = String.format(Locale.US, "%,d", big),
        kcalUnit = unit,
        fraction = if (goal != null && goal > 0) (actual / goal).toFloat().coerceIn(0f, 1f) else 0f,
        over = over,
        proteinText = "Protein " + proteinActual.toInt() + (proteinGoal?.let { " / ${it.toInt()}" } ?: "") + " g",
        slot = slot,
        slotLogged = slot in logged,
        usualItems = items,
    )
}

private fun ringBitmap(fraction: Float, over: Boolean): Bitmap {
    val size = 240
    val stroke = 22f
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rect = RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    paint.color = TRACK
    canvas.drawArc(rect, 0f, 360f, false, paint)
    if (fraction > 0f) {
        paint.color = if (over) DANGER.toArgb() else ACCENT.toArgb()
        canvas.drawArc(rect, -90f, 360f * fraction, false, paint)
    }
    return bitmap
}

class BlakeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = loadWidgetState(context)
        provideContent { WidgetContent(context, state) }
    }
}

@androidx.compose.runtime.Composable
private fun WidgetContent(context: Context, s: WidgetState) {
    val openApp = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    )
    val startSession = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("start_session", true)
    )

    Column(
        modifier = GlanceModifier.fillMaxSize().background(ColorProvider(SURFACE)).cornerRadius(24.dp).padding(12.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(openApp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(ringBitmap(s.fraction, s.over)),
                contentDescription = "Calories ring",
                modifier = GlanceModifier.size(64.dp),
            )
            Spacer(GlanceModifier.width(12.dp))
            Column {
                Text(
                    s.kcalText,
                    style = TextStyle(color = ColorProvider(INK), fontSize = 26.sp, fontWeight = FontWeight.Bold),
                )
                Text(s.kcalUnit, style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp))
                if (s.proteinText.isNotEmpty()) {
                    Text(s.proteinText, style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp))
                }
            }
        }
        Spacer(GlanceModifier.height(10.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val canLog = s.hasData && !s.slotLogged && s.usualItems != null
            val logLabel = when {
                !s.hasData -> "Open Blake"
                s.slotLogged -> "${s.slot.replaceFirstChar { it.uppercase() }} logged"
                s.usualItems == null -> "Log ${s.slot}"
                else -> "Log usual ${s.slot}"
            }
            Text(
                logLabel,
                style = TextStyle(
                    color = ColorProvider(if (canLog) ON_ACCENT else INK),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                ),
                modifier = GlanceModifier
                    .defaultWeight()
                    .background(ColorProvider(if (canLog) ACCENT else RAISED))
                    .cornerRadius(20.dp)
                    .padding(vertical = 10.dp, horizontal = 8.dp)
                    .clickable(if (canLog) actionRunCallback<LogUsualAction>() else openApp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                "Start session",
                style = TextStyle(color = ColorProvider(INK), fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                modifier = GlanceModifier
                    .defaultWeight()
                    .background(ColorProvider(RAISED))
                    .cornerRadius(20.dp)
                    .padding(vertical = 10.dp, horizontal = 8.dp)
                    .clickable(startSession),
            )
        }
    }
}

/** "Log usual <meal>" on the widget: queues the usual meal without opening the app. */
class LogUsualAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val state = loadWidgetState(context)
        val items = state.usualItems
        if (state.hasData && !state.slotLogged && items != null) {
            QuickLog.logUsual(context, state.slot, items)
        }
        BlakeWidget().update(context, glanceId)
    }
}

class BlakeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BlakeWidget()
}

/** Called whenever the saved /today data changes (after a sync, a refresh, a reset). */
object WidgetRefresh {
    suspend fun update(context: Context) {
        try {
            BlakeWidget().updateAll(context)
        } catch (e: Exception) {
            // no widget placed, or the launcher is not ready: nothing to refresh
        }
    }
}
