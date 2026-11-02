package com.blake.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blake.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

internal data class InsightPoint(val date: String, val value: Float, val logged: Boolean)

internal data class InsightMetric(
    val points: List<InsightPoint>,
    val goal: Float?,
    val avg: Float,
    val hitPct: Int?,
    val hitDays: Int?,
    val countedDays: Int?,
    val byGroup: Map<String, Int>,
)

internal data class DailyStreak(val current: Int, val best: Int, val todayDone: Boolean, val graceUsed: Boolean)
internal data class TrainingStreak(val currentWeeks: Int, val bestWeeks: Int, val days: Int, val goal: Int, val met: Boolean)

internal data class InsightsData(
    val metrics: Map<String, InsightMetric>,
    val meals: DailyStreak?,
    val protein: DailyStreak?,
    val training: TrainingStreak?,
)

class InsightsViewModel : ViewModel() {
    var days by mutableStateOf(14)
    private var data by mutableStateOf<InsightsData?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")

    private fun cacheKey(d: Int) = "insights_cache_$d"

    /** Cache-first: show the last copy for this range immediately, then refresh it. */
    fun load(context: Context, range: Int = days) {
        days = range
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        prefs.getString(cacheKey(range), null)?.let { parse(it)?.let { parsed -> data = parsed } }
        loading = true
        error = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.insights(range) }
            loading = false
            result.fold(
                onSuccess = { json ->
                    val parsed = parse(json)
                    if (parsed != null) {
                        data = parsed
                        prefs.edit().putString(cacheKey(range), json).apply()
                    } else {
                        error = "Couldn't read the insights."
                    }
                },
                onFailure = { if (data == null) error = "Couldn't load insights: ${it.message}" },
            )
        }
    }

    internal fun view(): InsightsData? = data

    private fun parse(json: String): InsightsData? = try {
        val root = JSONObject(json)
        val metrics = root.getJSONObject("metrics")
        val parsed = metrics.keys().asSequence().associateWith { key ->
            val m = metrics.getJSONObject(key)
            val pts = m.getJSONArray("points")
            val points = (0 until pts.length()).map {
                val p = pts.getJSONObject(it)
                InsightPoint(p.optString("date"), p.optDouble("value").toFloat(), p.optBoolean("logged"))
            }
            val groups = m.optJSONObject("by_group")
            InsightMetric(
                points = points,
                goal = if (m.isNull("goal")) null else m.optDouble("goal").toFloat(),
                avg = m.optDouble("avg").toFloat(),
                hitPct = if (m.has("hit_pct") && !m.isNull("hit_pct")) m.optInt("hit_pct") else null,
                hitDays = if (m.has("hit_days")) m.optInt("hit_days") else null,
                countedDays = if (m.has("counted_days")) m.optInt("counted_days") else null,
                byGroup = groups?.keys()?.asSequence()?.associateWith { g -> groups.optInt(g) } ?: emptyMap(),
            )
        }
        val st = root.optJSONObject("streaks")
        fun daily(name: String): DailyStreak? = st?.optJSONObject(name)?.let {
            DailyStreak(it.optInt("current"), it.optInt("best"), it.optBoolean("today_done"), it.optBoolean("grace_used_this_week"))
        }
        val tr = st?.optJSONObject("training")?.let {
            val week = it.optJSONObject("this_week")
            TrainingStreak(
                it.optInt("current_weeks"), it.optInt("best_weeks"),
                week?.optInt("days") ?: 0, week?.optInt("goal") ?: 0, week?.optBoolean("met") ?: false,
            )
        }
        InsightsData(parsed, daily("meals_logged"), daily("protein_target"), tr)
    } catch (e: Exception) {
        null
    }
}

@Composable
fun InsightsScreen(vm: InsightsViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.load(context) }
    val data = vm.view()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.xl, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text("Insights", style = MaterialTheme.typography.titleLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BlakeChip("14 days", selected = vm.days == 14, onClick = { vm.load(context, 14) })
            BlakeChip("30 days", selected = vm.days == 30, onClick = { vm.load(context, 30) })
        }

        if (vm.error.isNotBlank()) {
            Text(vm.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (data == null) {
            if (vm.loading) {
                Text("Loading...", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        StreaksCard(data)

        data.metrics["kcal"]?.let {
            MetricCard("Calories", it, headline = "${grouped(it.avg)} kcal a day", unit = "kcal", goalLabel = "target")
        }
        data.metrics["protein"]?.let {
            MetricCard("Protein", it, headline = "${grouped(it.avg)} g a day", unit = "g", goalLabel = "target")
        }
        data.metrics["spend"]?.let {
            MetricCard("Spend", it, headline = "${Format.currency(it.avg.toDouble())} a day", unit = "", goalLabel = "budget")
        }
        data.metrics["workout_days"]?.let { TrainingDaysCard(it, data.training) }
        data.metrics["volume"]?.let { if (it.byGroup.isNotEmpty()) VolumeCard(it) }
    }
}

private fun grouped(v: Float): String = String.format(Locale.US, "%,d", v.toInt())

@Composable
private fun StreaksCard(data: InsightsData) {
    BlakeCard {
        SectionLabel("Streaks")
        val rows = listOfNotNull(
            data.meals?.let { Triple("Meals logged", "${it.current} day${if (it.current == 1) "" else "s"}", streakNote(it)) },
            data.protein?.let { Triple("Protein target", "${it.current} day${if (it.current == 1) "" else "s"}", streakNote(it)) },
            data.training?.let {
                Triple(
                    "Training",
                    "${it.currentWeeks} week${if (it.currentWeeks == 1) "" else "s"}",
                    "${it.days} of ${it.goal} days this week" + if (it.met) " · done" else "",
                )
            },
        )
        if (rows.isEmpty()) {
            Text(
                "Log a few days and your streaks will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEach { (label, value, note) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun streakNote(s: DailyStreak): String {
    val parts = mutableListOf("best ${s.best}")
    parts.add(if (s.todayDone) "today done" else "today still open")
    if (s.graceUsed) parts.add("grace day used")
    return parts.joinToString(" · ")
}

@Composable
private fun MetricCard(title: String, m: InsightMetric, headline: String, unit: String, goalLabel: String) {
    val logged = m.points.any { it.logged }
    BlakeCard {
        SectionLabel(title)
        if (!logged) {
            Text("Nothing logged in this range yet.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BlakeCard
        }
        Text(headline, style = MaterialTheme.typography.titleLarge)
        val values = m.points.map { p -> if (p.logged || title == "Spend") p.value else null }
        Sparkline(
            values = values,
            goal = m.goal,
            description = "$title over ${m.points.size} days, average $headline",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shortDate(m.points.first().date), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(shortDate(m.points.last().date), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (m.goal != null) {
            val goalText = when (title) {
                "Spend" -> Format.currency(m.goal.toDouble())
                else -> "${grouped(m.goal)} $unit"
            }
            val pct = m.hitPct
            Text(
                if (pct != null) "Hit the $goalLabel on ${m.hitDays} of ${m.countedDays} days ($pct%). Dashed line: $goalText."
                else "Dashed line: $goalText ($goalLabel).",
                style = MaterialTheme.typography.bodySmall,
                color = if (pct != null && pct >= 70) MaterialTheme.blake.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrainingDaysCard(m: InsightMetric, training: TrainingStreak?) {
    val days = m.points.count { it.value > 0f }
    BlakeCard {
        SectionLabel("Training days")
        Text("$days of the last ${m.points.size} days", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            m.points.forEach { p ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(if (p.value > 0f) 1f else 0.12f)
                        .background(if (p.value > 0f) MaterialTheme.colorScheme.primary else MaterialTheme.blake.divider)
                )
            }
        }
        if (training != null && training.goal > 0) {
            Text(
                "This week: ${training.days} of ${training.goal} days" + if (training.met) ", goal met." else ".",
                style = MaterialTheme.typography.bodySmall,
                color = if (training.met) MaterialTheme.blake.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VolumeCard(m: InsightMetric) {
    val top = m.byGroup.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    BlakeCard {
        SectionLabel("Volume by muscle group")
        Text("Weight × reps over ${m.points.size} days", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        m.byGroup.entries.sortedByDescending { it.value }.forEach { (group, volume) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(group.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
                    Text("${grouped(volume.toFloat())} kg", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ThinBar(volume.toFloat() / top)
            }
        }
    }
}

/** A line through the logged days (gaps for unlogged ones), a dashed goal line and an
 * emphasised last point. */
@Composable
private fun Sparkline(values: List<Float?>, goal: Float?, description: String) {
    val line = MaterialTheme.colorScheme.primary
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val baseline = MaterialTheme.blake.divider
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .semantics { contentDescription = description }
    ) {
        val present = values.filterNotNull()
        if (present.isEmpty()) return@Canvas
        val top = max(present.max(), goal ?: 0f).coerceAtLeast(1f) * 1.1f
        val n = values.size
        fun x(i: Int) = if (n == 1) size.width / 2 else size.width * i / (n - 1)
        fun y(v: Float) = size.height - (v / top) * size.height

        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        if (goal != null) {
            drawLine(
                goalColor, Offset(0f, y(goal)), Offset(size.width, y(goal)),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )
        }
        var prev: Offset? = null
        var last: Offset? = null
        values.forEachIndexed { i, v ->
            if (v == null) {
                prev = null
            } else {
                val point = Offset(x(i), y(v))
                prev?.let { drawLine(line, it, point, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                prev = point
                last = point
            }
        }
        last?.let { drawCircle(line, radius = 4.dp.toPx(), center = it) }
    }
}

private fun shortDate(iso: String): String {
    val parts = iso.split("-")
    if (parts.size != 3) return iso
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${parts[2].toIntOrNull() ?: parts[2]} ${months.getOrElse((parts[1].toIntOrNull() ?: 1) - 1) { "" }}"
}
