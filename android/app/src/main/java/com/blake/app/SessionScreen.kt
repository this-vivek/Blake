package com.blake.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.blake.app.data.BlakeDatabase
import com.blake.app.data.CachedEventEntity
import com.blake.app.data.OutboxRepository
import com.blake.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DEFAULT_REST_SECONDS = 90
private const val WEIGHT_STEP = 2.5

/** Hands off the confirmed plan from the Today / Workout screens to SessionScreen without
 * serialising it through Navigation Compose's string-based route args. */
object WorkoutSessionStore {
    var pendingExercises: List<ExerciseItem> = emptyList()
    // Finishing a program-flagged session advances the program's day pointer (P4.4);
    // finishing an ad-hoc one doesn't.
    var isProgramSession: Boolean = false
}

private data class LoggedSet(val exercise: String, val group: String, val weightKg: Double, val reps: Int)

class SessionViewModel : ViewModel() {
    var exercises: List<ExerciseItem> = emptyList()
    var currentIndex by mutableStateOf(0)
    var setsLoggedForCurrent by mutableStateOf(0)
    var weightInput by mutableStateOf("")
    var repsInput by mutableStateOf(10)
    var suggestionText by mutableStateOf("")
    var sessionKcal by mutableStateOf(0.0)
    var statusText by mutableStateOf("")
    var finished by mutableStateOf(false)
    var restSecondsRemaining by mutableStateOf(0)
    private var restJob: Job? = null
    private var initialized = false

    var sessionStartMs = 0L
        private set
    var sessionEndMs by mutableStateOf<Long?>(null)
    private val loggedSets = mutableListOf<LoggedSet>()
    // PRs are only known once this session's sets reach the server (Epley is calculated
    // there, never duplicated client-side) — populated by checkForPrs after sync.
    var prsAfterSync by mutableStateOf<List<String>>(emptyList())

    val hasLoggedSets: Boolean get() = loggedSets.isNotEmpty()

    val volumeByGroup: Map<String, Double>
        get() = loggedSets.groupBy { it.group }
            .mapValues { (_, sets) -> sets.sumOf { it.weightKg * it.reps } }

    val durationMs: Long
        get() = (sessionEndMs ?: System.currentTimeMillis()) - sessionStartMs

    /** Warm-up moves go first, then the exercises. */
    fun init(exercises: List<ExerciseItem>) {
        if (initialized) return
        initialized = true
        val alreadyWarm = exercises.any { it.group == WarmUp.GROUP }
        val warm = if (alreadyWarm) emptyList() else WarmUp.forGroups(exercises.map { it.group })
        this.exercises = warm + exercises
        sessionStartMs = System.currentTimeMillis()
        resetInputsForCurrent()
    }

    val currentExercise: ExerciseItem? get() = exercises.getOrNull(currentIndex)
    val isWarmup: Boolean get() = currentExercise?.group == WarmUp.GROUP
    val warmupCount: Int get() = exercises.count { it.group == WarmUp.GROUP }

    private fun targetReps(reps: String): Int = reps.filter { it.isDigit() }.toIntOrNull() ?: 1

    private fun resetInputsForCurrent() {
        val ex = currentExercise ?: return
        setsLoggedForCurrent = 0
        statusText = ""
        weightInput = "0"
        suggestionText = ""
        repsInput = targetReps(ex.reps).coerceAtLeast(1)
    }

    fun stepWeight(delta: Double) {
        val next = ((weightInput.toDoubleOrNull() ?: 0.0) + delta).coerceAtLeast(0.0)
        weightInput = if (next == next.toInt().toDouble()) next.toInt().toString() else next.toString()
    }

    fun stepReps(delta: Int) {
        repsInput = (repsInput + delta).coerceIn(1, 99)
    }

    /** Prefills the weight from the server's deterministic progressive-overload suggestion
     * (workouts.suggested_next, P4.1) — the model never guesses this. */
    fun loadSuggestion(context: Context) {
        val ex = currentExercise ?: return
        if (ex.group == WarmUp.GROUP) return
        suggestionText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.suggestNextSet(ex.name) }
            result.onSuccess { json ->
                val obj = JSONObject(json)
                if (!obj.has("suggested_weight_kg") || obj.isNull("suggested_weight_kg")) return@onSuccess
                val suggested = obj.optDouble("suggested_weight_kg")
                val lastWeight = obj.optDouble("last_weight_kg")
                val lastReps = obj.optInt("last_reps")
                weightInput = if (suggested == suggested.toInt().toDouble()) {
                    suggested.toInt().toString()
                } else {
                    suggested.toString()
                }
                suggestionText = "Last time ${lastWeight.toInt()} kg × $lastReps, try $weightInput kg"
            }
        }
    }

    private fun startRestTimer() {
        restJob?.cancel()
        restSecondsRemaining = DEFAULT_REST_SECONDS
        restJob = viewModelScope.launch {
            while (restSecondsRemaining > 0) {
                delay(1000)
                restSecondsRemaining--
            }
        }
    }

    /** Queues the write and updates local state immediately — this never waits on the
     * network, so logging a set feels instant even offline. The kcal shown is a client-side
     * *estimate* (see KcalEstimate.kt) purely for that instant feedback; the server's own
     * computed value (summed in /today's kcal_burned_today) is the real number, reconciled
     * once SyncWorker actually delivers this set. Every set is saved the moment it is logged,
     * so closing the app mid-session loses nothing. */
    fun logSet(context: Context) {
        val ex = currentExercise ?: return
        // Blank means bodyweight (0 kg), same as the stepper showing 0.
        val weight = if (weightInput.isBlank()) 0.0 else weightInput.toDoubleOrNull()
        if (weight == null || weight < 0) {
            statusText = "Enter a valid weight"
            return
        }
        val reps = repsInput
        val estimatedKcal = estimateKcalForSet(ex.group)

        setsLoggedForCurrent++
        sessionKcal += estimatedKcal
        loggedSets.add(LoggedSet(ex.name, ex.group, weight, reps))
        statusText = "Set $setsLoggedForCurrent saved"
        startRestTimer()

        viewModelScope.launch {
            val bodyJson = JSONObject()
                .put("exercise", ex.name)
                .put("weight_kg", weight)
                .put("reps", reps)
                .toString()
            val outboxId = OutboxRepository.enqueue(context, "/log/set", "POST", bodyJson, "set")

            val payload = JSONObject()
                .put("exercise", ex.name.lowercase())
                .put("weight_kg", weight)
                .put("reps", reps)
                .put("kcal_burned", estimatedKcal)
            BlakeDatabase.get(context).eventDao().upsert(
                CachedEventEntity(
                    id = -outboxId,
                    module = "workouts",
                    kind = "set",
                    ts = System.currentTimeMillis() / 1000.0,
                    payloadJson = payload.toString(),
                    pending = true,
                )
            )
        }
    }

    /** A finished warm-up move counts as activity: kcal estimated here for instant feedback,
     * and queued to the server (POST /log/warmup), which computes the real number. */
    private fun logWarmup(context: Context, ex: ExerciseItem) {
        val timed = WarmUp.seconds(ex.reps)
        val seconds = if (timed > 0) timed else targetReps(ex.reps) * 3
        sessionKcal += estimateKcalForWarmup(seconds)
        viewModelScope.launch {
            val body = JSONObject().put("seconds", seconds).toString()
            OutboxRepository.enqueue(context, "/log/warmup", "POST", body, "warmup")
        }
    }

    fun nextExercise(context: Context) {
        currentExercise?.takeIf { it.group == WarmUp.GROUP }?.let { logWarmup(context, it) }
        restJob?.cancel()
        restSecondsRemaining = 0
        if (currentIndex < exercises.lastIndex) {
            currentIndex++
            resetInputsForCurrent()
        } else {
            finished = true
            sessionEndMs = System.currentTimeMillis()
            if (WorkoutSessionStore.isProgramSession) {
                val api = Prefs.apiFor(context)
                viewModelScope.launch { withContext(Dispatchers.IO) { api.completeProgramDay() } }
            }
        }
    }

    /** Ends early from the close button. Logged sets are already saved; the program day only
     * advances when the whole session is completed. */
    fun endEarly() {
        restJob?.cancel()
        restSecondsRemaining = 0
        finished = true
        sessionEndMs = System.currentTimeMillis()
    }

    /** Checks /today's server-computed PRs (Epley is only ever calculated there, see
     * log_set) for any exercise logged this session. Called more than once from the summary
     * screen since a set may still be mid-sync the first time this runs. */
    fun checkForPrs(context: Context) {
        val exerciseNames = loggedSets.map { it.exercise.lowercase() }.toSet()
        if (exerciseNames.isEmpty()) return
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.today() }
            result.onSuccess { json ->
                val prsArr = JSONObject(json).optJSONObject("training")?.optJSONArray("prs_today")
                    ?: return@onSuccess
                val names = mutableListOf<String>()
                for (i in 0 until prsArr.length()) {
                    val name = prsArr.getJSONObject(i).optString("exercise")
                    if (name in exerciseNames) names.add(name)
                }
                prsAfterSync = names
            }
        }
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

@Composable
fun SessionScreen(onFinish: () -> Unit, vm: SessionViewModel = viewModel()) {
    val context = LocalContext.current
    // Must happen before the first read of vm.currentExercise below. A LaunchedEffect runs
    // after composition, so the first frame saw an empty list and closed the screen at once
    // (this was the original "Start session does nothing" bug). init() is idempotent.
    remember { vm.init(WorkoutSessionStore.pendingExercises) }
    LaunchedEffect(vm.currentIndex) { vm.loadSuggestion(context) }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.finished) {
        while (!vm.finished) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    var confirmEnd by remember { mutableStateOf(false) }

    if (vm.finished) {
        SessionSummary(vm, onDone = onFinish)
        return
    }

    val ex = vm.currentExercise
    if (ex == null) {
        // Nothing queued (e.g. process restart): go back rather than show an empty screen.
        LaunchedEffect(Unit) { onFinish() }
        return
    }

    BackHandler { confirmEnd = true }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End session?") },
            text = {
                Text(
                    if (vm.hasLoggedSets) "The sets you logged are already saved."
                    else "Nothing has been logged yet."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    if (vm.hasLoggedSets) vm.endEarly() else onFinish()
                }) { Text("End session") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }

    val isLast = vm.currentIndex == vm.exercises.lastIndex
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.lg, bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        SessionTop(vm, elapsedMs = now - vm.sessionStartMs, onClose = { confirmEnd = true })
        ProgressSegments(vm)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (vm.isWarmup) WarmupBody(vm, ex) else ExerciseBody(vm, ex)
        }

        if (vm.isWarmup) {
            PrimaryButton("Next", onClick = { vm.nextExercise(context) })
        } else if (vm.setsLoggedForCurrent >= ex.sets) {
            // Every planned set is saved: the main action becomes moving on.
            PrimaryButton(
                text = if (isLast) "Finish session" else "Next exercise",
                onClick = { vm.nextExercise(context) },
            )
            QuietButton(
                text = "Log an extra set",
                onClick = { vm.logSet(context) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            PrimaryButton("Log set ${vm.setsLoggedForCurrent + 1}", onClick = { vm.logSet(context) })
            QuietButton(
                text = if (isLast) "Finish session" else "Next exercise",
                onClick = { vm.nextExercise(context) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun SessionTop(vm: SessionViewModel, elapsedMs: Long, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(BlakeIcons.Close, contentDescription = "End session",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(clock(elapsedMs), style = MaterialTheme.typography.headlineSmall)
            Text(
                "${vm.sessionKcal.toInt()} kcal burned",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressSegments(vm: SessionViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            vm.exercises.indices.forEach { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                i < vm.currentIndex -> MaterialTheme.colorScheme.primary
                                i == vm.currentIndex -> MaterialTheme.colorScheme.outline
                                else -> MaterialTheme.blake.divider
                            }
                        )
                )
            }
        }
        val label = if (vm.isWarmup) {
            "WARM-UP ${vm.currentIndex + 1} OF ${vm.warmupCount}"
        } else {
            val done = vm.currentIndex - vm.warmupCount + 1
            "EXERCISE $done OF ${vm.exercises.size - vm.warmupCount}"
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WarmupBody(vm: SessionViewModel, ex: ExerciseItem) {
    val seconds = WarmUp.seconds(ex.reps)
    var left by remember(vm.currentIndex) { mutableStateOf(seconds) }
    LaunchedEffect(vm.currentIndex) {
        while (left > 0) {
            delay(1000)
            left--
        }
    }
    ExerciseMedia(ex.name, "Warm-up", 200.dp)
    BlakeCard {
        SectionLabel("Warm-up")
        Text(ex.name, style = MaterialTheme.typography.displaySmall)
        if (seconds > 0) {
            Text(clock(left * 1000L), style = MaterialTheme.typography.displayLarge)
        } else {
            Text("${ex.reps} reps", style = MaterialTheme.typography.displayLarge)
        }
        if (ex.note.isNotBlank()) {
            Text(ex.note, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HowTo(ex.name)
}

@Composable
private fun ExerciseBody(vm: SessionViewModel, ex: ExerciseItem) {
    ExerciseMedia(ex.name, ex.group, 236.dp)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(ex.name, style = MaterialTheme.typography.titleLarge)
        Text(
            "Target: ${if (ex.sets > 1) "${ex.sets} sets × ${ex.reps}" else ex.reps}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        StepperCard(
            label = "Weight",
            value = vm.weightInput.ifBlank { "0" },
            unit = "kg",
            onMinus = { vm.stepWeight(-WEIGHT_STEP) },
            onPlus = { vm.stepWeight(WEIGHT_STEP) },
            modifier = Modifier.weight(1f),
        )
        StepperCard(
            label = "Reps",
            value = vm.repsInput.toString(),
            unit = "",
            onMinus = { vm.stepReps(-1) },
            onPlus = { vm.stepReps(1) },
            modifier = Modifier.weight(1f),
        )
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "Set ${vm.setsLoggedForCurrent + 1} of ${ex.sets}" +
                if (vm.suggestionText.isNotBlank()) " · ${vm.suggestionText}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
    if (vm.restSecondsRemaining > 0) {
        Text("Rest ${clock(vm.restSecondsRemaining * 1000L)}", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
    }
    if (vm.statusText.isNotBlank()) {
        Text(vm.statusText, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HowTo(ex.name)
}

/** The exercise photos loop between the start and end position (a short animation); moves
 * without a second photo show the single photo, or a placeholder when there is none. */
@Composable
private fun ExerciseMedia(name: String, group: String, height: Dp) {
    val context = LocalContext.current
    val frames = remember(name) {
        ExerciseGuide.get(context, name)?.frames?.takeIf { it.size >= 2 }
            ?: listOfNotNull(exerciseImageUrl(name))
    }
    var index by remember(name) { mutableStateOf(0) }
    LaunchedEffect(name) {
        if (frames.size > 1) {
            while (true) {
                delay(1100)
                index = (index + 1) % frames.size
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (frames.isEmpty()) {
                NoPhoto(group)
            } else {
                Crossfade(targetState = index, animationSpec = tween(450), label = "exercise-frames") { i ->
                    AsyncImage(
                        model = frames[i % frames.size],
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Numbered steps for the move, plus a button that opens a video demo. */
@Composable
private fun HowTo(name: String) {
    val context = LocalContext.current
    val guide = remember(name) { ExerciseGuide.get(context, name) }
    BlakeCard {
        SectionLabel("How to do it")
        if (guide != null && guide.steps.isNotEmpty()) {
            guide.steps.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(18.dp),
                    )
                    Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Text(
                "No written steps for this move yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SecondaryButton("Watch a demo", onClick = { ExerciseGuide.openDemo(context, name) })
    }
}

/** Neutral stand-in when the photo set has no trustworthy image for an exercise. */
@Composable
private fun NoPhoto(label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(
            BlakeIcons.Dumbbell,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp),
        )
        Text(
            label.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepperCard(
    label: String,
    value: String,
    unit: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(Radius.lg), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            SectionLabel(label)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.displaySmall)
                if (unit.isNotEmpty()) {
                    Text(
                        " $unit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StepButton("−", "Decrease $label", onMinus)
                StepButton("+", "Increase $label", onPlus)
            }
        }
    }
}

@Composable
private fun StepButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

@Composable
private fun SessionSummary(vm: SessionViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    // Checked twice: once immediately (covers an already-synced earlier set) and once after
    // a few seconds (covers a set still mid-sync when this screen first appeared).
    LaunchedEffect(Unit) {
        vm.checkForPrs(context)
        delay(4000)
        vm.checkForPrs(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
        Text("Session complete", style = MaterialTheme.typography.titleLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            BlakeCard(Modifier.weight(1f)) {
                SectionLabel("Time")
                Text(formatDuration(vm.durationMs), style = MaterialTheme.typography.displaySmall)
            }
            BlakeCard(Modifier.weight(1f)) {
                SectionLabel("Burned")
                Text("${vm.sessionKcal.toInt()} kcal", style = MaterialTheme.typography.displaySmall)
            }
        }

        if (vm.volumeByGroup.isNotEmpty()) {
            BlakeCard {
                SectionLabel("Volume (weight × reps)")
                vm.volumeByGroup.toSortedMap().forEach { (group, volume) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(group.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                        Text("${volume.toInt()} kg", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (vm.prsAfterSync.isNotEmpty()) {
            BlakeCard {
                SectionLabel("Personal records")
                vm.prsAfterSync.forEach { exercise ->
                    Text("PR: ${exercise.replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.blake.success)
                }
            }
        }

        }
        PrimaryButton("Done", onClick = onDone)
    }
}
