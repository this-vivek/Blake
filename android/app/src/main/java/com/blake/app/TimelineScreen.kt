package com.blake.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blake.app.data.BlakeDatabase
import com.blake.app.data.TodayCacheEntity
import com.blake.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val MODULES = listOf("meals", "workouts", "expenses", "tasks")

data class TimelineEntry(
    val id: Long,
    val ts: Double,
    val dayLabel: String,
    val text: String,
    val voided: Boolean,
    val pending: Boolean = false,
)

private sealed class TimelineRow {
    data class Header(val label: String) : TimelineRow()
    data class Item(val entry: TimelineEntry) : TimelineRow()
}

class TimelineViewModel : ViewModel() {
    var summaryText by mutableStateOf("Loading summary...")
    var summaryUpdatedAt by mutableStateOf<Long?>(null)
    var selectedModule by mutableStateOf(MODULES.first())
    var networkEntries by mutableStateOf<List<TimelineEntry>>(emptyList())
    var pendingEntries by mutableStateOf<List<TimelineEntry>>(emptyList())
    var isLoading by mutableStateOf(false)
    private var pendingCollectJob: Job? = null

    /** Pending (not-yet-synced) rows first, then the server's own history, so an offline
     * log shows up instantly without waiting for a network round-trip. */
    val entries: List<TimelineEntry>
        get() {
            val pendingIds = pendingEntries.map { it.id }.toSet()
            return (pendingEntries + networkEntries.filterNot { it.id in pendingIds })
                .sortedByDescending { it.ts }
        }

    /** Cache-first: render the last-known /today payload from Room immediately (if any),
     * then refresh from the network and persist the fresh copy for next time. */
    fun loadSummary(context: Context) {
        viewModelScope.launch {
            val cached = BlakeDatabase.get(context).todayDao().observe().first()
            if (cached != null) {
                summaryText = formatSummary(cached.json)
                summaryUpdatedAt = cached.updatedAt
            }
        }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.today() }
            result.onSuccess { json ->
                val now = System.currentTimeMillis()
                summaryText = formatSummary(json)
                summaryUpdatedAt = now
                BlakeDatabase.get(context).todayDao().upsert(
                    TodayCacheEntity(json = json, updatedAt = now)
                )
            }
            result.onFailure {
                if (summaryText == "Loading summary...") {
                    summaryText = "Couldn't load summary: ${it.message}"
                }
            }
        }
    }

    fun loadHistory(context: Context, module: String) {
        selectedModule = module
        isLoading = true

        pendingCollectJob?.cancel()
        pendingCollectJob = viewModelScope.launch {
            BlakeDatabase.get(context).eventDao().observeByModule(module).collect { cached ->
                pendingEntries = cached.filter { it.pending }.map { e ->
                    val payload = JSONObject(e.payloadJson)
                    TimelineEntry(
                        id = e.id, ts = e.ts, dayLabel = dayLabel(e.ts),
                        text = formatEntryText(e.kind, payload), voided = false, pending = true,
                    )
                }
            }
        }

        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.history(module, limit = 100) }
            isLoading = false
            networkEntries = result.fold(
                onSuccess = { parseTimeline(it) },
                onFailure = { listOf(TimelineEntry(-1, 0.0, "", "Error: ${it.message}", false)) }
            )
        }
    }

    fun undo(context: Context, eventId: Long) {
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.voidEvent(eventId.toInt()) }
            result.onSuccess {
                networkEntries = networkEntries.map {
                    if (it.id == eventId) it.copy(voided = true) else it
                }
            }
        }
    }
}

// Parses GET /today's aggregated shape (app/today.py's build_today), not /summary's —
// this is the one place that surfaces kcal_burned_today, which nothing else in the app shows.
private fun formatSummary(json: String): String {
    val obj = JSONObject(json)
    val parts = mutableListOf<String>()
    obj.optJSONObject("nutrition")?.optJSONObject("kcal")?.let {
        parts.add("Meals today: ${Format.kcal(it.optDouble("actual", 0.0))}")
    }
    obj.optJSONObject("spend")?.let {
        parts.add("Spent today: ${Format.currency(it.optDouble("actual", 0.0))}")
    }
    obj.optJSONObject("training")?.let {
        val kcalBurned = it.optDouble("kcal_burned_today", 0.0)
        if (kcalBurned > 0) parts.add("Burned today: ${Format.kcal(kcalBurned)}")
    }
    obj.optJSONArray("open_tasks")?.let {
        parts.add("Open tasks: ${it.length()}")
    }
    return if (parts.isEmpty()) "No data yet" else parts.joinToString("   |   ")
}

@Composable
private fun UpdatedAgoText(updatedAt: Long?) {
    if (updatedAt == null) return
    var now by remember(updatedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(updatedAt) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Text(
        "Updated ${formatAgo(updatedAt, now)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatAgo(updatedAt: Long, now: Long): String {
    val minutes = (now - updatedAt) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1440}d ago"
    }
}

private fun isSameDay(a: Calendar, b: Calendar) =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun dayLabel(epochSeconds: Double): String {
    val cal = Calendar.getInstance().apply { timeInMillis = (epochSeconds * 1000).toLong() }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        isSameDay(cal, today) -> "Today"
        isSameDay(cal, yesterday) -> "Yesterday"
        else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(cal.time)
    }
}

private fun formatEntryText(kind: String, payload: JSONObject): String = when (kind) {
    "meal" -> {
        val total = payload.optJSONObject("total")
        if (total != null) {
            "${payload.optString("meal")}: ${Format.kcal(total.optDouble("kcal", 0.0))}"
        } else {
            // optimistic/pending row — real macros aren't known client-side, don't fake them
            val itemCount = payload.optJSONArray("items")?.length() ?: 0
            "${payload.optString("meal")}: $itemCount item${if (itemCount == 1) "" else "s"}"
        }
    }
    "set" -> {
        val base = "${payload.optString("exercise")} ${Format.weightKg(payload.optDouble("weight_kg"))}" +
            " x${payload.optInt("reps")}"
        if (payload.has("e1rm")) "$base (e1RM ${Format.weightKg(payload.optDouble("e1rm"))})" else base
    }
    "expense" -> "${payload.optString("category")}: ${Format.currency(payload.optDouble("amount"))}" +
        (payload.optString("note").takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
    "task" -> "To-do: ${payload.optString("title")}"
    "task_done" -> "Done: ${payload.optString("title")}"
    "unmatched" -> "Unmatched: ${payload.optString("name")} (${payload.optDouble("grams")}g)"
    else -> payload.toString()
}

// /history returns the raw append-only log, including "void" events — those aren't shown as
// entries themselves, they just mark their target as voided (struck through, not hidden —
// seeing a correction happened is part of the audit trail).
private fun parseTimeline(json: String): List<TimelineEntry> {
    val events: JSONArray = JSONObject(json).optJSONArray("events") ?: JSONArray()
    val voidedIds = mutableSetOf<Long>()
    data class Raw(val id: Long, val ts: Double, val kind: String, val payload: JSONObject)
    val raw = mutableListOf<Raw>()
    for (i in 0 until events.length()) {
        val e = events.getJSONObject(i)
        val kind = e.optString("kind")
        val payload = e.optJSONObject("payload") ?: JSONObject()
        if (kind == "void") {
            voidedIds.add(payload.optLong("target_id"))
        } else {
            raw.add(Raw(e.optLong("id"), e.optDouble("ts"), kind, payload))
        }
    }
    return raw.map { r ->
        TimelineEntry(r.id, r.ts, dayLabel(r.ts), formatEntryText(r.kind, r.payload), r.id in voidedIds)
    }
}

private fun buildRows(entries: List<TimelineEntry>): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    var lastLabel: String? = null
    for (e in entries) {
        if (e.dayLabel != lastLabel) {
            rows.add(TimelineRow.Header(e.dayLabel))
            lastLabel = e.dayLabel
        }
        rows.add(TimelineRow.Item(e))
    }
    return rows
}

@Composable
fun TimelineScreen(vm: TimelineViewModel = viewModel()) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.loadSummary(context)
        vm.loadHistory(context, vm.selectedModule)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Timeline", style = MaterialTheme.typography.titleLarge)
            Text(
                vm.summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UpdatedAgoText(vm.summaryUpdatedAt)
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            MODULES.forEach { module ->
                BlakeChip(
                    text = module,
                    selected = vm.selectedModule == module,
                    onClick = { vm.loadHistory(context, module) },
                )
            }
        }

        if (vm.isLoading) {
            Text("Loading...", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val rows = remember(vm.entries) { buildRows(vm.entries) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(rows) { row ->
                    when (row) {
                        is TimelineRow.Header -> SectionLabel(
                            row.label,
                            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
                        )
                        is TimelineRow.Item -> TimelineEntryCard(row.entry, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEntryCard(entry: TimelineEntry, vm: TimelineViewModel) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Text(
                entry.text,
                style = if (entry.voided) {
                    MaterialTheme.typography.bodyLarge.copy(textDecoration = TextDecoration.LineThrough)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = if (entry.voided) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            if (entry.pending) {
                // Not yet confirmed by the server: no real event id to void against,
                // so Undo isn't offered until it syncs.
                Text("Syncing...", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (entry.voided) {
                Text("Undone", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (expanded) {
                QuietButton("Undo", onClick = { vm.undo(context, entry.id) })
            }
        }
    }
}
