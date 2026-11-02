package com.blake.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val MODULES = listOf("meals", "workouts", "expenses", "tasks")

private class Filter(val label: String, val module: String?)

private val FILTERS = listOf(
    Filter("All", null),
    Filter("Meals", "meals"),
    Filter("Training", "workouts"),
    Filter("Spend", "expenses"),
    Filter("Tasks", "tasks"),
)

data class TimelineEntry(
    val id: Long,
    val module: String,
    val ts: Double,
    val title: String,
    val detail: String,
    val value: String,
    val voided: Boolean,
    val pending: Boolean = false,
    val kcal: Double = 0.0,
    val spend: Double = 0.0,
    val isSet: Boolean = false,
    val isTaskDone: Boolean = false,
)

private class DayGroup(val key: String, val label: String, val entries: List<TimelineEntry>)

class TimelineViewModel : ViewModel() {
    var summaryUpdatedAt by mutableStateOf<Long?>(null)
    var filterModule by mutableStateOf<String?>(null)
    var networkEntries by mutableStateOf<List<TimelineEntry>>(emptyList())
    var pendingEntries by mutableStateOf<List<TimelineEntry>>(emptyList())
    var isLoading by mutableStateOf(false)
    var errorText by mutableStateOf("")
    private var pendingJobs: List<Job> = emptyList()
    private val pendingByModule = mutableMapOf<String, List<TimelineEntry>>()

    /** Pending (not-yet-synced) rows first, then the server's own history, so an offline
     * log shows up instantly without waiting for a network round-trip. */
    val entries: List<TimelineEntry>
        get() {
            val pendingIds = pendingEntries.map { it.id }.toSet()
            val all = (pendingEntries + networkEntries.filterNot { it.id in pendingIds })
            return all.filter { filterModule == null || it.module == filterModule }
                .sortedByDescending { it.ts }
        }

    /** Only used for the "Updated x ago" line: refreshes the same cached /today payload
     * the Today screen reads. */
    fun loadSummary(context: Context) {
        viewModelScope.launch {
            val cached = BlakeDatabase.get(context).todayDao().observe().first()
            if (cached != null) summaryUpdatedAt = cached.updatedAt
        }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.today() }
            result.onSuccess { json ->
                val now = System.currentTimeMillis()
                summaryUpdatedAt = now
                BlakeDatabase.get(context).todayDao().upsert(TodayCacheEntity(json = json, updatedAt = now))
            }
        }
    }

    fun load(context: Context) {
        isLoading = true
        errorText = ""

        pendingJobs.forEach { it.cancel() }
        pendingJobs = MODULES.map { module ->
            viewModelScope.launch {
                BlakeDatabase.get(context).eventDao().observeByModule(module).collect { cached ->
                    pendingByModule[module] = cached.filter { it.pending }.mapNotNull { e ->
                        buildEntry(e.id, module, e.kind, e.ts, JSONObject(e.payloadJson), voided = false, pending = true)
                    }
                    pendingEntries = pendingByModule.values.flatten()
                }
            }
        }

        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val results = coroutineScope {
                MODULES.map { module ->
                    async(Dispatchers.IO) { module to api.history(module, limit = 200) }
                }.awaitAll()
            }
            isLoading = false
            val merged = mutableListOf<TimelineEntry>()
            var failure: String? = null
            results.forEach { (module, result) ->
                result.fold(
                    onSuccess = { merged += parseModule(module, it) },
                    onFailure = { failure = it.message },
                )
            }
            networkEntries = merged
            errorText = if (failure != null && merged.isEmpty()) "Couldn't load history: $failure" else ""
        }
    }

    fun undo(context: Context, eventId: Long) {
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.voidEvent(eventId.toInt()) }
            result.onSuccess {
                networkEntries = networkEntries.map { if (it.id == eventId) it.copy(voided = true, kcal = 0.0, spend = 0.0, isSet = false, isTaskDone = false) else it }
            }
        }
    }
}

private fun isSameDay(a: Calendar, b: Calendar) =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun calendarOf(epochSeconds: Double) =
    Calendar.getInstance().apply { timeInMillis = (epochSeconds * 1000).toLong() }

private fun dayKey(epochSeconds: Double): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date((epochSeconds * 1000).toLong()))

private fun dayLabel(epochSeconds: Double): String {
    val cal = calendarOf(epochSeconds)
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        isSameDay(cal, today) -> "Today"
        isSameDay(cal, yesterday) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(cal.time)
    }
}

private fun timeLabel(epochSeconds: Double): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date((epochSeconds * 1000).toLong()))

private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

/** Turns one logged event into a readable row; null for kinds that are settings, not logs
 * (program, recipes, custom foods, goals) and for void markers. */
private fun buildEntry(
    id: Long, module: String, kind: String, ts: Double, p: JSONObject, voided: Boolean, pending: Boolean = false,
): TimelineEntry? = when (kind) {
    "meal" -> {
        val items = p.optJSONArray("items")
        val parts = (0 until (items?.length() ?: 0)).map {
            val o = items!!.getJSONObject(it)
            "${cap(o.optString("name"))} ${o.optInt("grams")} g"
        }
        val kcal = p.optJSONObject("total")?.optDouble("kcal", 0.0) ?: 0.0
        TimelineEntry(
            id, module, ts, cap(p.optString("meal", "meal")), parts.joinToString(" · "),
            if (pending) "syncing" else Format.kcal(kcal), voided, pending, kcal = if (voided) 0.0 else kcal,
        )
    }
    "set" -> {
        val weight = p.optDouble("weight_kg")
        val reps = p.optInt("reps")
        TimelineEntry(
            id, module, ts, cap(p.optString("exercise")),
            "${if (weight == 0.0) "Bodyweight" else Format.weightKg(weight)} × $reps",
            if (p.has("e1rm")) "e1RM ${Format.weightKg(p.optDouble("e1rm"))}" else "",
            voided, pending, isSet = !voided,
        )
    }
    "expense" -> {
        val amount = p.optDouble("amount")
        val note = p.optString("note").trim()
        TimelineEntry(
            id, module, ts, if (note.isNotEmpty()) note else cap(p.optString("category")),
            cap(p.optString("category")) + if (p.has("location")) " · location saved" else "",
            Format.currency(amount), voided, pending, spend = if (voided) 0.0 else amount,
        )
    }
    "task" -> TimelineEntry(id, module, ts, p.optString("title"), "Added", "", voided, pending)
    "task_done" -> TimelineEntry(id, module, ts, p.optString("title"), "Completed", "", voided, pending, isTaskDone = !voided)
    "unmatched" -> TimelineEntry(
        id, module, ts, cap(p.optString("name")), "Not matched to a food", "${p.optDouble("grams").toInt()} g", voided, pending,
    )
    else -> null
}

// /history returns the raw append-only log, including "void" events: those aren't rows
// themselves, they mark their target as voided (struck through, not hidden: seeing that a
// correction happened is part of the audit trail).
private fun parseModule(module: String, json: String): List<TimelineEntry> {
    val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
    val voidedIds = mutableSetOf<Long>()
    for (i in 0 until events.length()) {
        val e = events.getJSONObject(i)
        if (e.optString("kind") == "void") {
            voidedIds.add((e.optJSONObject("payload") ?: JSONObject()).optLong("target_id"))
        }
    }
    val out = mutableListOf<TimelineEntry>()
    for (i in 0 until events.length()) {
        val e = events.getJSONObject(i)
        val kind = e.optString("kind")
        if (kind == "void") continue
        val id = e.optLong("id")
        buildEntry(id, module, kind, e.optDouble("ts"), e.optJSONObject("payload") ?: JSONObject(), id in voidedIds)
            ?.let { out.add(it) }
    }
    return out
}

private fun groupByDay(entries: List<TimelineEntry>): List<DayGroup> {
    val groups = LinkedHashMap<String, MutableList<TimelineEntry>>()
    entries.forEach { groups.getOrPut(dayKey(it.ts)) { mutableListOf() }.add(it) }
    return groups.map { (key, list) -> DayGroup(key, dayLabel(list.first().ts), list) }
}

private fun summaryOf(entries: List<TimelineEntry>): String {
    val parts = mutableListOf<String>()
    val kcal = entries.sumOf { it.kcal }
    val spend = entries.sumOf { it.spend }
    val sets = entries.count { it.isSet }
    val done = entries.count { it.isTaskDone }
    if (kcal > 0) parts.add(Format.kcal(kcal))
    if (sets > 0) parts.add("$sets set${if (sets == 1) "" else "s"}")
    if (spend > 0) parts.add(Format.currency(spend))
    if (done > 0) parts.add("$done done")
    return if (parts.isEmpty()) "${entries.size} entr${if (entries.size == 1) "y" else "ies"}" else parts.joinToString(" · ")
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
    val minutes = (now - updatedAt) / 60_000
    Text(
        "Updated " + when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            else -> "${minutes / 60}h ago"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun TimelineScreen(vm: TimelineViewModel = viewModel()) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.loadSummary(context)
        vm.load(context)
    }

    val days = remember(vm.entries) { groupByDay(vm.entries) }
    val todayKey = remember { dayKey(System.currentTimeMillis() / 1000.0) }
    // Today opens by default; anything you tap stays as you left it.
    var toggled by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Timeline", style = MaterialTheme.typography.titleLarge)
            UpdatedAgoText(vm.summaryUpdatedAt)
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            FILTERS.forEach { f ->
                BlakeChip(f.label, selected = vm.filterModule == f.module, onClick = { vm.filterModule = f.module })
            }
        }

        when {
            vm.isLoading && days.isEmpty() -> Text(
                "Loading...", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            vm.errorText.isNotBlank() -> Text(
                vm.errorText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            )
            days.isEmpty() -> Text(
                "Nothing logged yet.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                contentPadding = PaddingValues(bottom = Spacing.xl),
            ) {
                items(days, key = { it.key }) { day ->
                    val open = toggled[day.key] ?: (day.key == todayKey)
                    DayCard(day, open, onToggle = { toggled = toggled + (day.key to !open) }, vm)
                }
            }
        }
    }
}

@Composable
private fun DayCard(day: DayGroup, open: Boolean, onToggle: () -> Unit, vm: TimelineViewModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.lg))
                    .clickable(onClick = onToggle)
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(day.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        summaryOf(day.entries),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    BlakeIcons.ChevronDown,
                    contentDescription = if (open) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).rotate(if (open) 180f else 0f),
                )
            }
            AnimatedVisibility(visible = open) {
                Column(modifier = Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.sm)) {
                    day.entries.forEach { entry ->
                        HorizontalDivider(color = MaterialTheme.blake.divider)
                        EntryRow(entry, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: TimelineEntry, vm: TimelineViewModel) {
    val context = LocalContext.current
    var showUndo by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val strike = if (entry.voided) TextDecoration.LineThrough else TextDecoration.None

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !entry.voided && !entry.pending) { showUndo = !showUndo }
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
            Text(
                timeLabel(entry.ts),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.width(60.dp).padding(top = 3.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyLarge.copy(textDecoration = strike),
                    color = if (entry.voided) muted else MaterialTheme.colorScheme.onSurface,
                )
                if (entry.detail.isNotBlank()) {
                    Text(
                        entry.detail,
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = strike),
                        color = muted,
                    )
                }
                if (entry.pending) Text("Syncing...", style = MaterialTheme.typography.bodySmall, color = muted)
                if (entry.voided) Text("Undone", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (entry.value.isNotBlank()) {
                Text(
                    entry.value,
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = strike),
                    color = muted,
                )
            }
        }
        if (showUndo && !entry.voided && !entry.pending) {
            QuietButton("Undo", onClick = { vm.undo(context, entry.id); showUndo = false })
        }
    }
}
