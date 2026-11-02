package com.blake.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UnmatchedItem(val id: Int, val name: String, val grams: Double)

class UnmatchedViewModel : ViewModel() {
    var items by mutableStateOf<List<UnmatchedItem>>(emptyList())
    var loading by mutableStateOf(false)
    // Per-item UI state, keyed by unmatched id — which row is expanded and in what mode.
    var expandedId by mutableStateOf<Int?>(null)
    var creatingNewFood by mutableStateOf(false)
    var mapToText by mutableStateOf("")
    var newFoodName by mutableStateOf("")
    var newFoodKcal by mutableStateOf("")
    var newFoodProtein by mutableStateOf("")
    var newFoodCarbs by mutableStateOf("")
    var newFoodFat by mutableStateOf("")
    var statusText by mutableStateOf("")

    fun load(context: Context) {
        loading = true
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.unmatchedItems() }
            loading = false
            result.onSuccess { json ->
                val arr = JSONObject(json).optJSONArray("items")
                items = if (arr == null) emptyList() else (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    UnmatchedItem(o.optInt("id"), o.optString("name"), o.optDouble("grams"))
                }
            }
        }
    }

    fun expand(item: UnmatchedItem) {
        expandedId = item.id
        creatingNewFood = false
        mapToText = ""
        newFoodName = item.name
        newFoodKcal = ""; newFoodProtein = ""; newFoodCarbs = ""; newFoodFat = ""
        statusText = ""
    }

    fun collapse() {
        expandedId = null
    }

    fun mapToExisting(context: Context, item: UnmatchedItem) {
        if (mapToText.isBlank()) return
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.resolveUnmatched(item.id, mapToText.trim(), item.grams)
            }
            result.fold(
                onSuccess = {
                    items = items.filterNot { it.id == item.id }
                    expandedId = null
                },
                onFailure = { statusText = "Error: ${it.message}" }
            )
        }
    }

    fun addAsNewFood(context: Context, item: UnmatchedItem) {
        val kcal = newFoodKcal.toDoubleOrNull()
        val protein = newFoodProtein.toDoubleOrNull()
        val carbs = newFoodCarbs.toDoubleOrNull()
        val fat = newFoodFat.toDoubleOrNull()
        val name = newFoodName.trim().lowercase()
        if (name.isBlank() || kcal == null || protein == null || carbs == null || fat == null) {
            statusText = "Fill in name and all four macro fields"
            return
        }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val addResult = withContext(Dispatchers.IO) {
                api.addCustomFood(name, mapOf("kcal" to kcal, "protein" to protein, "carbs" to carbs, "fat" to fat))
            }
            addResult.fold(
                onSuccess = {
                    viewModelScope.launch {
                        val resolveResult = withContext(Dispatchers.IO) {
                            api.resolveUnmatched(item.id, name, item.grams)
                        }
                        resolveResult.fold(
                            onSuccess = {
                                items = items.filterNot { it.id == item.id }
                                expandedId = null
                            },
                            onFailure = { statusText = "Added, but couldn't log it: ${it.message}" }
                        )
                    }
                },
                onFailure = { statusText = "Error: ${it.message}" }
            )
        }
    }
}

@Composable
fun UnmatchedScreen(onBack: () -> Unit, vm: UnmatchedViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.load(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
        }
        Text("Unmatched items", style = MaterialTheme.typography.titleLarge)
        Text(
            "Foods Blake couldn't recognize. Map each to a real food, or add it as a new one.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (vm.loading) {
            Text("Loading...")
        } else if (vm.items.isEmpty()) {
            Text("Nothing unmatched right now.", style = MaterialTheme.typography.bodyMedium)
        } else {
            vm.items.forEach { item -> UnmatchedRow(item, vm, context) }
        }
    }
}

@Composable
private fun UnmatchedRow(item: UnmatchedItem, vm: UnmatchedViewModel, context: Context) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${item.name} (${item.grams.toInt()}g)", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = {
                    if (vm.expandedId == item.id) vm.collapse() else vm.expand(item)
                }) {
                    Text(if (vm.expandedId == item.id) "Close" else "Resolve")
                }
            }

            if (vm.expandedId == item.id) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = !vm.creatingNewFood,
                        onClick = { vm.creatingNewFood = false },
                        label = { Text("Map to existing") },
                    )
                    FilterChip(
                        selected = vm.creatingNewFood,
                        onClick = { vm.creatingNewFood = true },
                        label = { Text("Add as new food") },
                    )
                }

                if (!vm.creatingNewFood) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = vm.mapToText,
                            onValueChange = { vm.mapToText = it },
                            label = { Text("Existing food name") },
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { vm.mapToExisting(context, item) }) { Text("Save") }
                    }
                } else {
                    OutlinedTextField(
                        value = vm.newFoodName, onValueChange = { vm.newFoodName = it },
                        label = { Text("Food name") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = vm.newFoodKcal, onValueChange = { vm.newFoodKcal = it },
                            label = { Text("kcal/100g") }, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = vm.newFoodProtein, onValueChange = { vm.newFoodProtein = it },
                            label = { Text("protein") }, modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = vm.newFoodCarbs, onValueChange = { vm.newFoodCarbs = it },
                            label = { Text("carbs") }, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = vm.newFoodFat, onValueChange = { vm.newFoodFat = it },
                            label = { Text("fat") }, modifier = Modifier.weight(1f),
                        )
                    }
                    Button(onClick = { vm.addAsNewFood(context, item) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add & log")
                    }
                }

                if (vm.statusText.isNotBlank()) {
                    Text(vm.statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
