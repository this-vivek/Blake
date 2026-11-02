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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blake.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UnmatchedItem(val id: Int, val name: String, val grams: Double)

class UnmatchedViewModel : ViewModel() {
    var items by mutableStateOf<List<UnmatchedItem>>(emptyList())
    var loading by mutableStateOf(false)
    var expandedId by mutableStateOf<Int?>(null)
    var mapMode by mutableStateOf(false)
    var mapToText by mutableStateOf("")
    var newFoodName by mutableStateOf("")
    var kcal by mutableStateOf("")
    var protein by mutableStateOf("")
    var carbs by mutableStateOf("")
    var fat by mutableStateOf("")
    var statusText by mutableStateOf("")

    // Lookup state: where the numbers in the fields came from, and whether you changed them.
    var lookingUp by mutableStateOf(false)
    var proposalSource by mutableStateOf("")
    var proposalFromModel by mutableStateOf(false)
    var fieldsEdited by mutableStateOf(false)

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

    /** Opening an item looks its values up straight away; nothing is saved until you approve. */
    fun expand(context: Context, item: UnmatchedItem) {
        expandedId = item.id
        mapMode = false
        mapToText = ""
        newFoodName = item.name
        clearProposal()
        statusText = ""
        lookUp(context)
    }

    fun collapse() {
        expandedId = null
    }

    private fun clearProposal() {
        kcal = ""; protein = ""; carbs = ""; fat = ""
        proposalSource = ""
        proposalFromModel = false
        fieldsEdited = false
    }

    fun lookUp(context: Context) {
        val name = newFoodName.trim().lowercase()
        if (name.isBlank()) return
        lookingUp = true
        statusText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.proposeFood(name) }
            lookingUp = false
            result.fold(
                onSuccess = { json ->
                    val obj = JSONObject(json)
                    val proposal = obj.optJSONObject("proposal")
                    if (proposal == null) {
                        statusText = obj.optString("error", "No values found. Enter them by hand.")
                        return@fold
                    }
                    val per100 = proposal.optJSONObject("per100") ?: return@fold
                    kcal = trimmed(per100.optDouble("kcal"))
                    protein = trimmed(per100.optDouble("protein"))
                    carbs = trimmed(per100.optDouble("carbs"))
                    fat = trimmed(per100.optDouble("fat"))
                    proposalSource = proposal.optString("source")
                    proposalFromModel = proposal.optString("origin") == "model"
                    fieldsEdited = false
                },
                onFailure = { statusText = "Couldn't look it up: ${it.message}. Enter the values by hand." },
            )
        }
    }

    private fun trimmed(v: Double): String =
        if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()

    fun markEdited() {
        if (proposalSource.isNotEmpty()) fieldsEdited = true
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

    /** The approval step: saves the food with where its numbers came from. */
    fun approveAndLog(context: Context, item: UnmatchedItem) {
        val k = kcal.toDoubleOrNull()
        val p = protein.toDoubleOrNull()
        val c = carbs.toDoubleOrNull()
        val f = fat.toDoubleOrNull()
        val name = newFoodName.trim().lowercase()
        if (name.isBlank() || k == null || p == null || c == null || f == null) {
            statusText = "Fill in the name and all four values"
            return
        }
        val source = when {
            proposalSource.isEmpty() -> "user"
            fieldsEdited -> "User-edited, started from: $proposalSource"
            else -> proposalSource
        }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val addResult = withContext(Dispatchers.IO) {
                api.addCustomFood(name, mapOf("kcal" to k, "protein" to p, "carbs" to c, "fat" to f), source)
            }
            addResult.fold(
                onSuccess = {
                    val resolveResult = withContext(Dispatchers.IO) { api.resolveUnmatched(item.id, name, item.grams) }
                    resolveResult.fold(
                        onSuccess = {
                            items = items.filterNot { it.id == item.id }
                            expandedId = null
                        },
                        onFailure = { statusText = "Food saved, but couldn't log it: ${it.message}" }
                    )
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.lg, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            QuietButton("Back", onClick = onBack)
            Text("Unmatched items", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Foods Blake didn't recognise. Open one to look up its values, check them, and add it. Nothing is saved until you approve.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            vm.loading -> Text("Loading...", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.items.isEmpty() -> Text("Nothing unmatched right now.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> vm.items.forEach { item -> UnmatchedRow(item, vm, context) }
        }
    }
}

@Composable
private fun UnmatchedRow(item: UnmatchedItem, vm: UnmatchedViewModel, context: Context) {
    val open = vm.expandedId == item.id
    BlakeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
                Text("${item.grams.toInt()} g logged", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            QuietButton(if (open) "Close" else "Resolve", onClick = {
                if (open) vm.collapse() else vm.expand(context, item)
            })
        }

        if (open) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                BlakeChip("Look up & add", selected = !vm.mapMode, onClick = { vm.mapMode = false })
                BlakeChip("Map to existing", selected = vm.mapMode, onClick = { vm.mapMode = true })
            }

            if (vm.mapMode) {
                OutlinedTextField(
                    value = vm.mapToText,
                    onValueChange = { vm.mapToText = it },
                    label = { Text("Existing food name") },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton("Save", enabled = vm.mapToText.isNotBlank(), onClick = { vm.mapToExisting(context, item) })
            } else {
                LookUpAndAdd(item, vm, context)
            }

            if (vm.statusText.isNotBlank()) {
                Text(vm.statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LookUpAndAdd(item: UnmatchedItem, vm: UnmatchedViewModel, context: Context) {
    OutlinedTextField(
        value = vm.newFoodName,
        onValueChange = { vm.newFoodName = it },
        label = { Text("Food name") },
        singleLine = true,
        shape = RoundedCornerShape(Radius.md),
        modifier = Modifier.fillMaxWidth(),
    )

    when {
        vm.lookingUp -> Text("Looking up...", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.proposalSource.isNotEmpty() -> Text(
            (if (vm.fieldsEdited) "Edited by you. Started from: " else "Proposed from: ") + vm.proposalSource,
            style = MaterialTheme.typography.bodySmall,
            color = if (vm.proposalFromModel) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionLabel("Per 100 g")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        NutrientField("kcal", vm.kcal, { vm.kcal = it; vm.markEdited() }, Modifier.weight(1f))
        NutrientField("protein", vm.protein, { vm.protein = it; vm.markEdited() }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        NutrientField("carbs", vm.carbs, { vm.carbs = it; vm.markEdited() }, Modifier.weight(1f))
        NutrientField("fat", vm.fat, { vm.fat = it; vm.markEdited() }, Modifier.weight(1f))
    }

    PrimaryButton("Approve & log", enabled = !vm.lookingUp, onClick = { vm.approveAndLog(context, item) })
    QuietButton("Look up again", enabled = !vm.lookingUp, onClick = { vm.lookUp(context) })
}

@Composable
private fun NutrientField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(Radius.md),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
