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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
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

    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val collapseRangePx = with(density) { (HERO_EXPANDED - HERO_COLLAPSED).toPx() }
    // 0 = ring fully open, 1 = tucked into the top-left bar. Driven directly by the scroll
    // position, so it follows the finger both ways.
    val progress = (scroll.value / collapseRangePx).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            // Room for the expanded header, which is drawn above the list.
            Spacer(Modifier.height(HERO_EXPANDED))
            NextMeal(vm, context)
            if (vm.editingSlot != null) MealEditor(vm, context)
            Nudges(vm.todayJson)
            TrainingCard(vm, context, onStartSession, onNavigateToWorkout)
            SpendCard(vm, onAdd = { showSpend = true })
            TasksCard(vm, context)
        }
        CollapsingHero(vm, progress)
    }

    if (showSpend) {
        SpendSheet(vm, onDismiss = { showSpend = false })
    }
}

private val HERO_EXPANDED = 376.dp
private val HERO_COLLAPSED = 76.dp
private val RING_BIG = 200.dp
private val RING_SMALL = 44.dp

/** Title, kcal ring and protein bar that shrink into a compact bar (ring + "kcal left" at
 * the top left, protein at the right) as the page scrolls, and grow back when it scrolls up. */
@Composable
private fun CollapsingHero(vm: TodayViewModel, progress: Float) {
    val nutrition = vm.todayJson?.optJSONObject("nutrition")
    val kcal = nutrition?.optJSONObject("kcal")
    val protein = nutrition?.optJSONObject("protein_g")
    val actual = kcal?.optDouble("actual") ?: 0.0
    val goal = optionalDouble(kcal, "goal")
    val proteinActual = protein?.optDouble("actual") ?: 0.0
    val proteinGoal = optionalDouble(protein, "goal")

    val over = goal != null && actual > goal
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
    val date = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date()) }

    val expandedAlpha = (1f - progress * 2f).coerceIn(0f, 1f)
    val collapsedAlpha = (progress * 2f - 1f).coerceIn(0f, 1f)
    val titleAlpha = (1f - progress * 1.6f).coerceIn(0f, 1f)
    val height = lerp(HERO_EXPANDED, HERO_COLLAPSED, progress)
    val ringSize = lerp(RING_BIG, RING_SMALL, progress)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.background)
    ) {
        val ringX = lerp((maxWidth - RING_BIG) / 2, Spacing.lg, progress)
        val ringY = lerp(84.dp, (HERO_COLLAPSED - RING_SMALL) / 2, progress)

        // Title and date (fade out first)
        Column(
            modifier = Modifier
                .padding(start = Spacing.lg, top = Spacing.xl)
                .alpha(titleAlpha),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Today", style = MaterialTheme.typography.titleLarge)
            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // The ring: one element that moves and resizes
        KcalRing(
            actual = actual,
            goal = goal,
            size = ringSize,
            stroke = lerp(8.dp, 4.dp, progress),
            centerText = big,
            centerUnit = unit,
            centerAlpha = expandedAlpha,
            modifier = Modifier.offset(x = ringX, y = ringY),
        )

        // Compact readout beside the small ring
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = Spacing.lg + RING_SMALL + Spacing.md, end = Spacing.lg)
                .fillMaxWidth()
                .alpha(collapsedAlpha),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("$big $unit", style = MaterialTheme.typography.titleSmall)
                if (goal != null) {
                    Text(
                        "of ${grouped(goal.toInt())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Protein " + Format.grams(proteinActual) + (proteinGoal?.let { " / ${Format.grams(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Protein bar (fades out as the bar collapses)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.lg)
                .alpha(expandedAlpha),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
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

        HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomCenter).alpha(collapsedAlpha),
            color = MaterialTheme.blake.divider,
        )
    }
}

@Composable
private fun KcalRing(
    actual: Double,
    goal: Double?,
    size: Dp,
    stroke: Dp,
    centerText: String,
    centerUnit: String,
    centerAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val over = goal != null && actual > goal
    val fraction = if (goal != null && goal > 0) (actual / goal).toFloat().coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.blake.divider
    val progressColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2
            val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(strokePx))
            if (fraction > 0f) {
                drawArc(progressColor, -90f, 360f * fraction, false, Offset(inset, inset), arcSize,
                    style = Stroke(strokePx, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(centerAlpha)) {
            Text(
                centerText,
                style = MaterialTheme.typography.displayMedium.copy(fontSize = (44f * size.value / 200f).sp),
            )
            Text(centerUnit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
