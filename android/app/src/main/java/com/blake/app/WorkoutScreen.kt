package com.blake.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class MuscleGroupOption(val id: String, val label: String, val emoji: String)

private val MUSCLE_GROUP_OPTIONS = listOf(
    MuscleGroupOption("chest", "Chest", "🏋️"),
    MuscleGroupOption("back", "Back", "🦾"),
    MuscleGroupOption("legs", "Legs", "🦵"),
    MuscleGroupOption("shoulders", "Shoulders", "🤸"),
    MuscleGroupOption("biceps", "Biceps", "💪"),
    MuscleGroupOption("triceps", "Triceps", "🥊"),
    MuscleGroupOption("core", "Core", "🔥"),
    MuscleGroupOption("cardio", "Cardio", "🏃"),
)

data class ExerciseItem(val group: String, val name: String, val sets: Int, val reps: String)

class WorkoutViewModel : ViewModel() {
    var location by mutableStateOf<String?>(null)
    var selectedGroups by mutableStateOf<Set<String>>(emptySet())
    var showSheet by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var exercises by mutableStateOf<List<ExerciseItem>>(emptyList())
    var errorText by mutableStateOf("")

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
                onSuccess = { json -> exercises = parseExercises(json) },
                onFailure = { errorText = "Error: ${it.message}" }
            )
        }
    }
}

private fun parseExercises(json: String): List<ExerciseItem> {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(vm: WorkoutViewModel = viewModel()) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Where are you training?", style = MaterialTheme.typography.titleLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LocationTile(
                emoji = "🏢", label = "Gym",
                selected = vm.location == "gym",
                modifier = Modifier.weight(1f),
                onClick = { vm.selectLocation("gym") },
            )
            LocationTile(
                emoji = "🏠", label = "Home",
                selected = vm.location == "home",
                modifier = Modifier.weight(1f),
                onClick = { vm.selectLocation("home") },
            )
        }

        if (vm.loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (vm.errorText.isNotBlank()) {
            Text(vm.errorText, color = MaterialTheme.colorScheme.error)
        }

        if (vm.exercises.isNotEmpty()) {
            val byGroup = vm.exercises.groupBy { it.group }
            byGroup.forEach { (group, items) ->
                val option = MUSCLE_GROUP_OPTIONS.find { it.id == group }
                Text(
                    if (option != null) "${option.emoji} ${option.label}" else group,
                    style = MaterialTheme.typography.titleMedium,
                )
                items.forEach { ex -> ExerciseCard(ex) }
            }
        }
    }

    if (vm.showSheet) {
        MuscleGroupSheet(vm, onDismiss = { vm.showSheet = false }, onConfirm = { vm.confirmGroups(context) })
    }
}

@Composable
private fun LocationTile(
    emoji: String,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .height(120.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 8.dp else 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(emoji, style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MuscleGroupSheet(vm: WorkoutViewModel, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp).padding(bottom = 32.dp)) {
            Text("What are you training today?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(260.dp),
            ) {
                items(MUSCLE_GROUP_OPTIONS) { option ->
                    MuscleGroupTile(
                        option = option,
                        selected = option.id in vm.selectedGroups,
                        onClick = { vm.toggleGroup(option.id) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onConfirm,
                enabled = vm.selectedGroups.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Get My Plan (${vm.selectedGroups.size} selected)")
            }
        }
    }
}

@Composable
private fun MuscleGroupTile(option: MuscleGroupOption, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(option.emoji, style = MaterialTheme.typography.headlineMedium)
            Text(option.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ExerciseCard(ex: ExerciseItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ex.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                if (ex.sets > 1) "${ex.sets} × ${ex.reps}" else ex.reps,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
