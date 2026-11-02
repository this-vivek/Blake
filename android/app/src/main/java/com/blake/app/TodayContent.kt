package com.blake.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blake.app.ui.theme.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun grouped(n: Int): String = String.format(Locale.US, "%,d", n)

private fun prettyGroup(raw: String): String =
    raw.split(",", "_", "+").map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(" + ") { it.replaceFirstChar { c -> c.uppercase() } }

@Composable
fun TodayScreen(
    onNavigateToWorkout: () -> Unit = {},
    onStartSession: () -> Unit = {},
    vm: TodayViewModel = viewModel(),
) {
    val context = LocalContext.current
    var showSpend by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.loadToday(context)
        vm.loadProgram(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.xl, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        TodayHeader()
        Hero(vm)
        NextMeal(vm, context)
        if (vm.editingSlot != null) MealEditor(vm, context)
        Nudges(vm.todayJson)
        TrainingCard(vm, context, onStartSession, onNavigateToWorkout)
        SpendCard(vm, onAdd = { showSpend = true })
        TasksCard(vm, context)
    }

    if (showSpend) {
        SpendSheet(vm, onDismiss = { showSpend = false })
    }
}

@Composable
private fun TodayHeader() {
    val date = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Today", style = MaterialTheme.typography.titleLarge)
        Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hero(vm: TodayViewModel) {
    val nutrition = vm.todayJson?.optJSONObject("nutrition")
    val kcal = nutrition?.optJSONObject("kcal")
    val protein = nutrition?.optJSONObject("protein_g")
    val actual = kcal?.optDouble("actual") ?: 0.0
    val goal = optionalDouble(kcal, "goal")
    val proteinActual = protein?.optDouble("actual") ?: 0.0
    val proteinGoal = optionalDouble(protein, "goal")

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        KcalRing(actual, goal)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionLabel("Protein")
                Text(
                    Format.grams(proteinActual) + (proteinGoal?.let { " of ${Format.grams(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val fraction = if (proteinGoal != null && proteinGoal > 0) (proteinActual / proteinGoal).toFloat() else 0f
            ThinBar(fraction)
        }
    }
}

@Composable
private fun KcalRing(actual: Double, goal: Double?) {
    val over = goal != null && actual > goal
    val fraction = if (goal != null && goal > 0) (actual / goal).toFloat().coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.blake.divider
    val progress = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val big = when {
        goal == null -> grouped(actual.toInt())
        over -> grouped((actual - goal).toInt())
        else -> grouped((goal - actual).toInt())
    }
    val unit = when {
        goal == null -> "kcal eaten"
        over -> "kcal over"
        else -> "kcal left"
    }
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(progress, -90f, 360f * fraction, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(big, style = MaterialTheme.typography.displayMedium)
            Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The single primary action: log the meal that fits the time of day. */
@Composable
private fun NextMeal(vm: TodayViewModel, context: Context) {
    val nutrition = vm.todayJson?.optJSONObject("nutrition")
    val serverLogged = nutrition?.optJSONArray("meal_slots_logged")?.let { arr ->
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    } ?: emptySet()
    val logged = serverLogged + vm.locallyLoggedSlots
    val slot = defaultMealForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    val slotName = slot.replaceFirstChar { it.uppercase() }
    val usual = nutrition?.optJSONObject("usual")?.optJSONObject(slot)
    val hasUsual = (usual?.optInt("times_logged", 0) ?: 0) > 0

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (slot in logged) {
            SecondaryButton("Log another meal", onClick = { vm.startEditingSlot(context, "snack") })
            Text(
                "$slotName logged",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.blake.success,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        } else {
            PrimaryButton("Log ${slot}", onClick = {
                if (hasUsual && usual != null) vm.logUsual(context, slot, usual) else vm.startEditingSlot(context, slot)
            })
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (hasUsual && usual != null) usualLine(usual) else "Tap to pick what you had",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                QuietButton("Edit", onClick = { vm.startEditingSlot(context, slot) })
            }
        }
        if (vm.logResultText.isNotBlank()) {
            Text(
                vm.logResultText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        }
    }
}

private fun usualLine(usual: JSONObject): String {
    val items = usual.optJSONArray("items")
    val names = if (items == null) emptyList() else (0 until items.length()).map { items.getJSONObject(it).optString("name") }
    val kcal = usual.optJSONObject("total")?.optDouble("kcal", 0.0) ?: 0.0
    return "Usual: ${names.take(3).joinToString(", ")} · ${kcal.toInt()} kcal"
}

@Composable
private fun Nudges(todayJson: JSONObject?) {
    val nudges = todayJson?.optJSONArray("nudges")
    if (nudges == null || nudges.length() == 0) return
    BlakeCard {
        for (i in 0 until nudges.length()) {
            Text(nudges.getString(i), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TrainingCard(
    vm: TodayViewModel,
    context: Context,
    onStartSession: () -> Unit,
    onNavigateToWorkout: () -> Unit,
) {
    val training = vm.todayJson?.optJSONObject("training")
    val setCount = training?.optJSONArray("sets_today")?.length() ?: 0
    val kcalBurned = training?.optDouble("kcal_burned_today") ?: 0.0
    val prCount = training?.optJSONArray("prs_today")?.length() ?: 0

    BlakeCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Training")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BlakeChip("Gym", selected = vm.trainingLocation == "gym", onClick = { vm.trainingLocation = "gym" })
                BlakeChip("Home", selected = vm.trainingLocation == "home", onClick = { vm.trainingLocation = "home" })
            }
        }
        when {
            !vm.programLoaded -> Text(
                "Loading your plan...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            vm.programIsRest -> {
                Text("Rest day", style = MaterialTheme.typography.displaySmall)
                Text("No session planned. Recovery counts.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Text(prettyGroup(vm.programMuscleGroup ?: "Training"), style = MaterialTheme.typography.displaySmall)
                Text(
                    "Warm-up first, then your exercises",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (setCount > 0) {
            Text(
                "$setCount set${if (setCount == 1) "" else "s"} logged · ${kcalBurned.toInt()} kcal burned" +
                    if (prCount > 0) " · $prCount PR${if (prCount > 1) "s" else ""}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.blake.success,
            )
        }
        if (vm.startError.isNotBlank()) {
            Text(vm.startError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!vm.programIsRest) {
            SecondaryButton(
                text = if (vm.startingSession) "Starting..." else "Start session",
                enabled = vm.programLoaded && !vm.startingSession,
                onClick = { vm.startSession(context, onStartSession) },
            )
        }
        QuietButton("Choose a different workout", onClick = onNavigateToWorkout)
    }
}

@Composable
private fun SpendCard(vm: TodayViewModel, onAdd: () -> Unit) {
    val spend = vm.todayJson?.optJSONObject("spend")
    val actual = (spend?.optDouble("actual") ?: 0.0) + vm.pendingSpend
    val goal = optionalDouble(spend, "goal")
    BlakeCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Spend")
            QuietButton("Add", onClick = onAdd)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(Format.currency(actual), style = MaterialTheme.typography.displaySmall)
            if (goal != null) {
                Text(
                    "of ${Format.currency(goal)} today",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        if (goal != null && goal > 0) {
            ThinBar(
                (actual / goal).toFloat(),
                fill = if (actual > goal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (actual > goal) {
                Text("Over budget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (vm.expenseResultText.isNotBlank()) {
            Text(vm.expenseResultText, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TasksCard(vm: TodayViewModel, context: Context) {
    val openTasks = vm.todayJson?.optJSONArray("open_tasks")
    val visible = (0 until (openTasks?.length() ?: 0)).map { openTasks!!.getJSONObject(it) }
        .filterNot { it.optInt("id") in vm.completingTaskIds }
    BlakeCard {
        SectionLabel("Tasks")
        if (visible.isEmpty()) {
            Text("Nothing open", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            visible.forEach { task ->
                val id = task.optInt("id")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { vm.completeTask(context, id) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), CircleShape)
                    )
                    Text(task.optString("title"), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun MealEditor(vm: TodayViewModel, context: Context) {
    BlakeCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("${vm.mealType} suggestion")
            Row {
                QuietButton(if (vm.mealRefreshing) "Refreshing..." else "Refresh", enabled = !vm.mealRefreshing,
                    onClick = { vm.refreshMeal(context) })
                QuietButton("Close", onClick = { vm.editingSlot = null })
            }
        }
        if (vm.mealLoading) {
            Text("Loading...", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            vm.mealNames.forEachIndexed { idx, name ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = vm.mealGrams.getOrElse(idx) { "" },
                        onValueChange = { vm.updateGram(idx, it) },
                        suffix = { Text("g") },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.sm),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(112.dp),
                    )
                }
            }
            if (vm.mealTotalText.isNotBlank()) {
                Text(vm.mealTotalText, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.mealNames.isNotEmpty()) {
                PrimaryButton("Log this meal", onClick = { vm.logMeal(context) })
            }
        }
    }
}

private val SPEND_CATEGORIES = listOf("food", "transport", "shopping", "bills", "other")

/** Amount, a free-text description and a category; saved through the offline outbox. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpendSheet(vm: TodayViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("food") }
    val value = amount.toDoubleOrNull() ?: 0.0
    val prefs = remember { context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }

    fun save() {
        vm.logExpense(context, value, category, note)
        onDismiss()
    }
    // Asked once, the first time you save a spend; denying never blocks saving.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { save() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.blake.scrim,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Add spend", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                prefix = { Text("₹", style = MaterialTheme.typography.displaySmall) },
                textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Start),
                singleLine = true,
                shape = RoundedCornerShape(Radius.md),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = sheetFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Description") },
                placeholder = { Text("Auto to office") },
                singleLine = true,
                shape = RoundedCornerShape(Radius.md),
                colors = sheetFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionLabel("Category")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    SPEND_CATEGORIES.forEach { c ->
                        BlakeChip(c, selected = c == category, onClick = { category = c })
                    }
                }
            }

            Text(
                "Saved with where you spent it, if you allow location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PrimaryButton("Save", enabled = value > 0, onClick = {
                val asked = prefs.getBoolean("location_asked", false)
                if (LocationHelper.hasPermission(context) || asked) {
                    save()
                } else {
                    prefs.edit().putBoolean("location_asked", true).apply()
                    permissionLauncher.launch(LocationHelper.PERMISSIONS)
                }
            })
        }
    }
}

@Composable
private fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedBorderColor = MaterialTheme.colorScheme.outline,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
