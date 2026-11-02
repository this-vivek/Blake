package com.blake.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.blake.app.ui.pop.PopButton
import com.blake.app.ui.pop.PopTile
import com.blake.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class MuscleGroupOption(val id: String, val label: String)

private val MUSCLE_GROUP_OPTIONS = listOf(
    MuscleGroupOption("chest", "Chest"),
    MuscleGroupOption("back", "Back"),
    MuscleGroupOption("legs", "Legs"),
    MuscleGroupOption("shoulders", "Shoulders"),
    MuscleGroupOption("biceps", "Biceps"),
    MuscleGroupOption("triceps", "Triceps"),
    MuscleGroupOption("core", "Core"),
    MuscleGroupOption("cardio", "Cardio"),
)

data class ExerciseItem(
    val group: String,
    val name: String,
    val sets: Int,
    val reps: String,
    val note: String = "",
)

class WorkoutViewModel : ViewModel() {
    var location by mutableStateOf<String?>(null)
    var selectedGroups by mutableStateOf<Set<String>>(emptySet())
    var showSheet by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var exercises by mutableStateOf<List<ExerciseItem>>(emptyList())
    var errorText by mutableStateOf("")

    // Opt-in "follow my plan" mode (ROADMAP-NEXTGEN.md P4) — sits alongside the gym/home
    // picker above rather than replacing it, since the picker already works well.
    var programDayIndex by mutableStateOf<Int?>(null)
    var programMuscleGroup by mutableStateOf<String?>(null)
    var programIsRest by mutableStateOf(false)
    var programLoaded by mutableStateOf(false)

    fun loadProgramToday(context: Context) {
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.programToday() }
            result.onSuccess { json ->
                val obj = JSONObject(json)
                programDayIndex = obj.optInt("index")
                val day = obj.optJSONObject("day")
                programIsRest = day?.optString("type") == "rest"
                programMuscleGroup = if (!programIsRest) day?.optString("muscle_group") else null
                programLoaded = true
            }
        }
    }

    fun markRestDayComplete(context: Context) {
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { api.completeProgramDay() }
            loadProgramToday(context)
        }
    }

    /** Skips the location-choice sheet since the muscle group is already known from the
     * program — the session is flagged so finishing it advances the program's pointer
     * (WorkoutSessionStore.isProgramSession), same as completing any other day. */
    fun startProgramDay(context: Context, location: String) {
        val group = programMuscleGroup ?: return
        loading = true
        errorText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.suggestWorkout(location, group) }
            loading = false
            result.fold(
                onSuccess = { json ->
                    exercises = parseExercises(json)
                    WorkoutSessionStore.isProgramSession = true
                },
                onFailure = { errorText = "Error: ${it.message}" }
            )
        }
    }

    fun selectLocation(loc: String) {
        location = loc
        selectedGroups = emptySet()
        exercises = emptyList()
        errorText = ""
        showSheet = true
    }

    fun toggleGroup(group: String) {
        selectedGroups = if (group in selectedGroups) selectedGroups - group else selectedGroups + group
    }

    fun confirmGroups(context: Context) {
        val loc = location ?: return
        if (selectedGroups.isEmpty()) return
        showSheet = false
        loading = true
        errorText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.suggestWorkout(loc, selectedGroups.joinToString(","))
            }
            loading = false
            result.fold(
                onSuccess = { json ->
                    exercises = parseExercises(json)
                    WorkoutSessionStore.isProgramSession = false
                },
                onFailure = { errorText = "Error: ${it.message}" }
            )
        }
    }
}

internal fun parseExercises(json: String): List<ExerciseItem> {
    val groups = JSONObject(json).optJSONArray("groups") ?: return emptyList()
    val out = mutableListOf<ExerciseItem>()
    for (i in 0 until groups.length()) {
        val g = groups.getJSONObject(i)
        val groupName = g.optString("muscle_group")
        val exercisesArr = g.optJSONArray("exercises") ?: continue
        for (j in 0 until exercisesArr.length()) {
            val e = exercisesArr.getJSONObject(j)
            out.add(ExerciseItem(groupName, e.optString("exercise"), e.optInt("sets"), e.optString("reps")))
        }
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutScreen(
    onStartSession: (List<ExerciseItem>) -> Unit,
    onBack: () -> Unit = {},
    vm: WorkoutViewModel = viewModel(),
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.lg, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            QuietButton("Back", onClick = onBack)
            Text("Choose a workout", style = MaterialTheme.typography.titleLarge)
        }

        BlakeCard {
            SectionLabel("Where")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BlakeChip("Gym", selected = vm.location == "gym", onClick = {
                    vm.location = if (vm.location == "gym") null else "gym"
                    vm.exercises = emptyList()
                })
                BlakeChip("Home", selected = vm.location == "home", onClick = {
                    vm.location = if (vm.location == "home") null else "home"
                    vm.exercises = emptyList()
                })
            }
            SectionLabel("Muscle groups")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                MUSCLE_GROUP_OPTIONS.forEach { option ->
                    BlakeChip(
                        option.label,
                        selected = option.id in vm.selectedGroups,
                        onClick = {
                            vm.toggleGroup(option.id)
                            vm.exercises = emptyList()
                        },
                    )
                }
            }
            val ready = vm.location != null && vm.selectedGroups.isNotEmpty() && !vm.loading
            val hasPlan = vm.exercises.isNotEmpty()
            if (hasPlan) {
                SecondaryButton("Refresh plan", enabled = ready, onClick = { vm.confirmGroups(context) })
            } else {
                PrimaryButton(
                    text = if (vm.loading) "Loading..." else "Get plan",
                    enabled = ready,
                    onClick = { vm.confirmGroups(context) },
                )
            }
            if (vm.location == null || vm.selectedGroups.isEmpty()) {
                Text(
                    "Pick where you are and at least one muscle group.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (vm.errorText.isNotBlank()) {
            Text(vm.errorText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (vm.exercises.isNotEmpty()) {
            vm.exercises.groupBy { it.group }.forEach { (group, items) ->
                val option = MUSCLE_GROUP_OPTIONS.find { it.id == group }
                SectionLabel(option?.label ?: group)
                items.forEach { ex -> ExerciseCard(ex) }
            }
            PrimaryButton("Start session", onClick = { onStartSession(vm.exercises) })
        }
    }
}

@Composable
private fun ExerciseCard(ex: ExerciseItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val imageUrl = exerciseImageUrl(ex.name)
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = ex.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(Radius.sm)),
                )
            }
            Text(ex.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (ex.sets > 1) "${ex.sets} × ${ex.reps}" else ex.reps,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
