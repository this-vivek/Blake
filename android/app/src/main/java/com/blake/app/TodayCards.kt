package com.blake.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blake.app.ui.theme.*
import java.util.Locale

private fun oneDecimal(v: Double): String =
    if (v == v.toInt().toDouble()) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

internal fun kgText(v: Double): String = String.format(Locale.US, "%.1f kg", v)

/** A bottom sheet with a fixed height and scrolling content (a sheet whose height changes can
 * re-settle and dismiss itself). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlakeSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.blake.scrim,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.75f)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            content = content,
        )
    }
}

// ---- Habits ----------------------------------------------------------------------------------

private data class HabitRow(
    val name: String, val kind: String, val value: Double, val target: Double?,
    val unit: String, val streak: Int, val done: Boolean,
)

private fun habitRows(vm: TodayViewModel): List<HabitRow> {
    val arr = vm.todayJson?.optJSONArray("habits")
    val rows = (0 until (arr?.length() ?: 0)).map { i ->
        val o = arr!!.getJSONObject(i)
        val name = o.optString("name")
        val kind = o.optString("kind")
        val target = if (o.has("target") && !o.isNull("target")) o.optDouble("target") else null
        val value = o.optDouble("value") + (vm.pendingHabit[name] ?: 0.0)
        HabitRow(
            name, kind, value, target, o.optString("unit"),
            o.optJSONObject("streak")?.optInt("current") ?: 0,
            if (kind == "yesno") value > 0 else value >= (target ?: 1.0),
        )
    }.toMutableList()
    // Water is built in: it is always there, and the server creates it on the first log.
    if (rows.none { it.name == "water" }) {
        val v = vm.pendingHabit["water"] ?: 0.0
        rows.add(0, HabitRow("water", "count", v, 8.0, "glass", 0, v >= 8.0))
    }
    return rows
}

@Composable
internal fun HabitsCard(vm: TodayViewModel, context: Context, onNew: () -> Unit) {
    BlakeCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Habits")
            QuietButton("New habit", onClick = onNew)
        }
        habitRows(vm).forEach { h ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(h.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                        val progress = if (h.kind == "count" && h.target != null) {
                            val unit = h.unit.ifBlank { "" }
                            "${oneDecimal(h.value)} of ${oneDecimal(h.target)} $unit".trim() + if (h.target != 1.0 && unit.isNotBlank()) "s" else ""
                        } else if (h.done) "Done today" else "Not done yet"
                        Text(
                            progress + if (h.streak > 0) " · ${h.streak} day streak" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (h.done) MaterialTheme.blake.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (h.kind == "count") {
                        SecondaryButtonSmall("+1") { vm.logHabit(context, h.name, 1.0) }
                    } else {
                        SecondaryButtonSmall(if (h.done) "Done" else "Mark done", enabled = !h.done) { vm.logHabit(context, h.name, 1.0) }
                    }
                }
                if (h.kind == "count" && h.target != null && h.target > 0) {
                    ThinBar((h.value / h.target).toFloat())
                }
            }
        }
    }
}

@Composable
private fun SecondaryButtonSmall(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    BlakeChip(text, selected = false, onClick = { if (enabled) onClick() })
}

@Composable
internal fun NewHabitSheet(vm: TodayViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    val targetValue = target.toDoubleOrNull()
    BlakeSheet(onDismiss) {
        Text("New habit", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name") }, placeholder = { Text("Meditation") },
            singleLine = true, shape = RoundedCornerShape(Radius.md), colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BlakeChip("Yes / no", selected = !count, onClick = { count = false })
            BlakeChip("Count", selected = count, onClick = { count = true })
        }
        if (count) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = target, onValueChange = { target = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Daily target") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(Radius.md), colors = sheetFieldColors(), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = unit, onValueChange = { unit = it },
                    label = { Text("Unit") }, placeholder = { Text("page") }, singleLine = true,
                    shape = RoundedCornerShape(Radius.md), colors = sheetFieldColors(), modifier = Modifier.weight(1f),
                )
            }
        }
        if (vm.habitError.isNotBlank()) {
            Text(vm.habitError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        PrimaryButton(
            "Create habit",
            enabled = name.isNotBlank() && (!count || (targetValue != null && targetValue > 0)),
            onClick = { vm.createHabit(context, name, if (count) "count" else "yesno", targetValue, unit, onDismiss) },
        )
    }
}

// ---- Weight ----------------------------------------------------------------------------------

@Composable
internal fun WeightCard(vm: TodayViewModel, onLog: () -> Unit) {
    val w = vm.todayJson?.optJSONObject("weight")
    val pending = vm.pendingWeightKg
    val latest = pending ?: w?.let { if (it.isNull("latest_kg")) null else it.optDouble("latest_kg") }
    val avg7 = if (pending == null) w?.let { if (it.isNull("avg7")) null else it.optDouble("avg7") } else null
    val loggedToday = pending != null || (w != null && !w.isNull("kg"))
    val lastDate = w?.optString("latest_date").orEmpty()
    BlakeCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Weight")
            QuietButton("Log", onClick = onLog)
        }
        if (latest == null) {
            Text("No weigh-ins yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(kgText(latest), style = MaterialTheme.typography.displaySmall)
            Text(
                (if (loggedToday) "Logged today" else "Last logged $lastDate") +
                    (avg7?.let { " · 7-day average ${kgText(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun WeightSheet(vm: TodayViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val w = vm.todayJson?.optJSONObject("weight")
    val last = w?.let { if (it.isNull("latest_kg")) null else it.optDouble("latest_kg") }
    var text by remember { mutableStateOf(last?.let { oneDecimal(it) } ?: "") }
    val kg = text.toDoubleOrNull()
    BlakeSheet(onDismiss) {
        Text("Log weight", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Weight (kg)") }, singleLine = true,
            textStyle = MaterialTheme.typography.displaySmall,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(Radius.md), colors = sheetFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "One weigh-in counts per day; logging again replaces it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton("Save", enabled = kg != null && kg in 20.0..300.0, onClick = {
            vm.logWeight(context, kg!!)
            onDismiss()
        })
    }
}

// ---- Sleep -----------------------------------------------------------------------------------

@Composable
internal fun SleepCard(vm: TodayViewModel, onLog: () -> Unit) {
    val s = vm.todayJson?.optJSONObject("sleep")
    val pending = vm.pendingSleep
    val hours = pending?.first ?: s?.optDouble("hours")
    val quality = if (pending != null) pending.second else s?.let { if (it.isNull("quality")) null else it.optInt("quality") }
    BlakeCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Sleep")
            QuietButton("Log", onClick = onLog)
        }
        if (hours == null) {
            Text("Not logged for last night", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("${oneDecimal(hours)} h", style = MaterialTheme.typography.displaySmall)
            Text(
                if (quality != null) "Quality $quality of 5" else "No quality rating",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SleepSheet(vm: TodayViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var hours by remember { mutableStateOf(7.0) }
    var quality by remember { mutableStateOf<Int?>(null) }
    BlakeSheet(onDismiss) {
        Text("Log last night's sleep", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            QuietButton("− 0.5", onClick = { hours = (hours - 0.5).coerceAtLeast(0.5) })
            Text("${oneDecimal(hours)} h", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = Spacing.lg))
            QuietButton("+ 0.5", onClick = { hours = (hours + 0.5).coerceAtMost(24.0) })
        }
        SectionLabel("Quality (optional)")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            (1..5).forEach { q -> BlakeChip("$q", selected = quality == q, onClick = { quality = if (quality == q) null else q }) }
        }
        PrimaryButton("Save", onClick = {
            vm.logSleep(context, hours, quality)
            onDismiss()
        })
    }
}
