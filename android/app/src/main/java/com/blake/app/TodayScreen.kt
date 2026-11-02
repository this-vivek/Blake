package com.blake.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.blake.app.data.BlakeDatabase
import com.blake.app.data.CachedEventEntity
import com.blake.app.data.OutboxRepository
import com.blake.app.data.TodayCacheEntity
import com.blake.app.ui.theme.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

private val MEAL_SLOTS = listOf("breakfast", "lunch", "dinner", "snack")
private val SLOT_LABELS = mapOf("breakfast" to "B", "lunch" to "L", "dinner" to "D", "snack" to "S")

private fun defaultMealForHour(hour: Int): String = when (hour) {
    in 5..10 -> "breakfast"
    in 11..15 -> "lunch"
    in 16..18 -> "snack"
    in 19..22 -> "dinner"
    else -> "snack"
}

class TodayViewModel : ViewModel() {
    var todayJson by mutableStateOf<JSONObject?>(null)
    // ✓ shown for a slot the moment it's logged, before the server round-trip confirms it —
    // /today's own meal_slots_logged won't reflect a pending outbox write until it syncs.
    var locallyLoggedSlots by mutableStateOf<Set<String>>(emptySet())
    var editingSlot by mutableStateOf<String?>(null)

    var mealType by mutableStateOf(
        defaultMealForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    )
    var mealLoading by mutableStateOf(false)
    var mealTotalText by mutableStateOf("")
    var mealNames by mutableStateOf<List<String>>(emptyList())
    var mealGrams by mutableStateOf<List<String>>(emptyList())
    var logResultText by mutableStateOf("")

    var mealRefreshing by mutableStateOf(false)

    var expenseAmount by mutableStateOf("")
    var expenseResultText by mutableStateOf("")
    // Added to /today's own spend.actual for display, since a queued expense doesn't land
    // server-side until SyncWorker drains it — a lighter optimistic update than meal/set's
    // full outbox+cached_events row, scoped to just this card's running total.
    var pendingSpend by mutableStateOf(0.0)

    var completingTaskIds by mutableStateOf<Set<Int>>(emptySet())

    /** Cache-first: render the last-known /today payload from Room immediately (if any),
     * then refresh from the network and persist the fresh copy for next time. */
    fun loadToday(context: Context) {
        viewModelScope.launch {
            val cached = BlakeDatabase.get(context).todayDao().observe().first()
            if (cached != null) todayJson = JSONObject(cached.json)
        }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.today() }
            result.onSuccess { json ->
                todayJson = JSONObject(json)
                BlakeDatabase.get(context).todayDao().upsert(
                    TodayCacheEntity(json = json, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    fun startEditingSlot(context: Context, slot: String) {
        editingSlot = slot
        mealType = slot
        mealNames = emptyList()
        mealTotalText = ""
        suggestMeal(context)
    }

    fun suggestMeal(context: Context) {
        mealLoading = true
        logResultText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.suggestMeal(mealType) }
            mealLoading = false
            applyMealResult(result)
        }
    }

    /** LLM-backed refresh for variety — see app/meal_refresh.py. Falls back to the plain
     * deterministic suggestion server-side if the LLM call fails, so this never errors out. */
    fun refreshMeal(context: Context) {
        mealRefreshing = true
        logResultText = ""
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.suggestMealRefresh(mealType) }
            mealRefreshing = false
            applyMealResult(result)
        }
    }

    private fun applyMealResult(result: Result<String>) {
        result.onSuccess { json ->
            val obj = JSONObject(json)
            val itemsArr = obj.optJSONArray("items")
            val names = mutableListOf<String>()
            val grams = mutableListOf<String>()
            if (itemsArr != null) {
                for (i in 0 until itemsArr.length()) {
                    val it = itemsArr.getJSONObject(i)
                    names.add(it.optString("name"))
                    grams.add(it.optInt("grams").toString())
                }
            }
            mealNames = names
            mealGrams = grams
            val total = obj.optJSONObject("total")
            mealTotalText = if (total != null) {
                "Approx: ${Format.kcal(total.optDouble("kcal", 0.0))}, " +
                    "${Format.grams(total.optDouble("protein", 0.0))} protein"
            } else ""
        }
        result.onFailure {
            mealNames = emptyList()
            mealTotalText = "Error: ${it.message}"
        }
    }

    fun updateGram(index: Int, value: String) {
        mealGrams = mealGrams.toMutableList().also { it[index] = value }
    }

    /** Queues the write to the outbox instead of waiting on the network — logging an already-
     * known suggestion works instantly offline, and syncs exactly once when reconnected
     * (WorkManager's network constraint + the backend's client_id idempotency). */
    fun logMeal(context: Context) {
        val items = mealNames.indices.mapNotNull { i ->
            val grams = mealGrams[i].toDoubleOrNull()
            if (grams != null && grams > 0) mealNames[i] to grams else null
        }
        if (items.isEmpty()) return
        val slot = mealType
        locallyLoggedSlots = locallyLoggedSlots + slot
        viewModelScope.launch {
            val itemsJson = JSONArray()
            items.forEach { (name, grams) ->
                itemsJson.put(JSONObject().put("name", name).put("grams", grams))
            }
            val bodyJson = JSONObject().put("meal", slot).put("items", itemsJson).toString()
            val outboxId = OutboxRepository.enqueue(context, "/log/meal", "POST", bodyJson, "meal")

            // Optimistic row: shows what was submitted, not fabricated macros — real numbers
            // land once SyncWorker reconciles this with the server's response.
            val payload = JSONObject().put("meal", slot).put("items", itemsJson)
            BlakeDatabase.get(context).eventDao().upsert(
                CachedEventEntity(
                    id = -outboxId,
                    module = "meals",
                    kind = "meal",
                    ts = System.currentTimeMillis() / 1000.0,
                    payloadJson = payload.toString(),
                    pending = true,
                )
            )
            logResultText = "Logged for $slot — syncing..."
            editingSlot = null
        }
    }

    /** One-tap log of a slot's established "usual" combo (app/today.py's usual_combo) —
     * skips the suggestion editor entirely, using the same outbox/optimistic-row pattern as
     * logMeal so it works offline too. */
    fun logUsual(context: Context, slot: String, usual: JSONObject) {
        val items = usual.optJSONArray("items") ?: return
        locallyLoggedSlots = locallyLoggedSlots + slot
        viewModelScope.launch {
            val bodyJson = JSONObject().put("meal", slot).put("items", items).toString()
            val outboxId = OutboxRepository.enqueue(context, "/log/meal", "POST", bodyJson, "meal")
            val payload = JSONObject().put("meal", slot).put("items", items)
            BlakeDatabase.get(context).eventDao().upsert(
                CachedEventEntity(
                    id = -outboxId,
                    module = "meals",
                    kind = "meal",
                    ts = System.currentTimeMillis() / 1000.0,
                    payloadJson = payload.toString(),
                    pending = true,
                )
            )
            logResultText = "Logged usual $slot — syncing..."
        }
    }

    /** Quick-add defaults to "food" — the roadmap calls for a quick-add, not a full category
     * picker; "food" covers the common case and the exact category can still be corrected
     * later via chat or the web-only history view if ever needed. */
    fun logExpense(context: Context) {
        val amount = expenseAmount.toDoubleOrNull() ?: return
        if (amount <= 0) return
        pendingSpend += amount
        viewModelScope.launch {
            val bodyJson = JSONObject().put("amount", amount).put("category", "food").toString()
            OutboxRepository.enqueue(context, "/log/expense", "POST", bodyJson, "expense")
            expenseResultText = "Logged ₹${amount.toInt()} — syncing..."
            expenseAmount = ""
        }
    }

    /** Optimistically hides the task immediately; /tasks/{id}/complete is already safe to
     * retry (completing an already-done task just 404s, never double-completes), so this
     * doesn't need the outbox — a direct call matches how Undo/void already work elsewhere. */
    fun completeTask(context: Context, taskId: Int) {
        completingTaskIds = completingTaskIds + taskId
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.completeTask(taskId) }
            result.onFailure { completingTaskIds = completingTaskIds - taskId }
        }
    }

    // Free-text/photo logging, docked on Today — replaces the old standalone Log tab.
    var chatInput by mutableStateOf("")
    var chatImageUri by mutableStateOf<Uri?>(null)
    var chatLoading by mutableStateOf(false)
    var chatResultText by mutableStateOf("")
    var chatEventIds by mutableStateOf<List<Pair<String, Int>>>(emptyList())
    var chatUndone by mutableStateOf<Set<Int>>(emptySet())

    /** Tries the network directly first, for the normal case — this keeps chat's rich
     * immediate result (summary text, per-entry Undo) rather than always deferring to the
     * outbox. Only on failure (most commonly: offline) does it fall back to queueing, since
     * an image can't travel through the outbox's instant-local-row pattern the way meal/set
     * logging does — there's no structured payload to show until the server actually parses it. */
    fun sendChat(context: Context, imageBytes: ByteArray?, imageMimeType: String?) {
        if (chatInput.isBlank() && imageBytes == null) return
        chatLoading = true
        chatResultText = ""
        chatEventIds = emptyList()
        chatUndone = emptySet()
        val api = Prefs.apiFor(context)
        val text = chatInput
        lastSentText = text
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                api.chat(text, imageBytes, imageMimeType ?: "image/jpeg")
            }
            chatLoading = false
            result.fold(
                onSuccess = { json ->
                    val (summary, ids) = parseChatResult(json)
                    chatResultText = summary
                    chatEventIds = ids
                    chatInput = ""
                    chatImageUri = null
                },
                onFailure = {
                    viewModelScope.launch(Dispatchers.IO) {
                        OutboxRepository.enqueueChat(context, text, imageBytes, imageMimeType)
                    }
                    chatResultText = "Offline — queued, will parse once reconnected"
                    chatInput = ""
                    chatImageUri = null
                }
            )
        }
    }

    var lastSentText by mutableStateOf("")

    fun undoChatEvent(context: Context, eventId: Int) {
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { api.voidEvent(eventId) }
            result.onSuccess { chatUndone = chatUndone + eventId }
            result.onFailure { chatResultText = "Couldn't undo: ${it.message}" }
        }
    }

    /** Void every event from the last reply, then put the original text back for editing. */
    fun editChatResult(context: Context) {
        val ids = chatEventIds.map { it.second }.filter { it !in chatUndone }
        val api = Prefs.apiFor(context)
        viewModelScope.launch {
            ids.forEach { id ->
                val result = withContext(Dispatchers.IO) { api.voidEvent(id) }
                result.onSuccess { chatUndone = chatUndone + id }
            }
            chatInput = lastSentText
            chatResultText = ""
            chatEventIds = emptyList()
        }
    }
}

private fun parseChatResult(json: String): Pair<String, List<Pair<String, Int>>> {
    val obj = JSONObject(json)
    val text = obj.optString("text")
    val calls = obj.optJSONArray("calls")
    val ids = mutableListOf<Pair<String, Int>>()
    if (calls != null) {
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val output = call.optJSONObject("output") ?: continue
            if (output.has("event_id")) {
                ids.add(call.optString("name") to output.optInt("event_id"))
            }
        }
    }
    return text to ids
}

private fun optionalDouble(obj: JSONObject?, key: String): Double? {
    if (obj == null || !obj.has(key) || obj.isNull(key)) return null
    return obj.optDouble(key)
}

@Composable
fun TodayScreen(onNavigateToWorkout: () -> Unit = {}, vm: TodayViewModel = viewModel()) {
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.loadToday(context) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NutritionCard(vm, context)

        if (vm.editingSlot != null) {
            MealEditor(vm, context)
        }

        TrainingCard(vm.todayJson, onNavigateToWorkout)
        SpendCard(vm, context)
        TasksCard(vm, context)
        NudgesRow(vm.todayJson)

        Divider()

        Text("Log anything", style = MaterialTheme.typography.titleMedium)
        if (vm.chatResultText.isNotBlank()) {
            ChatResultCard(vm)
        }
        Composer(vm)
    }
}

@Composable
private fun TrainingCard(todayJson: JSONObject?, onNavigateToWorkout: () -> Unit) {
    val training = todayJson?.optJSONObject("training")
    val setsToday = training?.optJSONArray("sets_today")
    val setCount = setsToday?.length() ?: 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Training", style = MaterialTheme.typography.titleMedium)
            if (setCount == 0) {
                Text("No sets logged yet today", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onNavigateToWorkout, modifier = Modifier.fillMaxWidth()) {
                    Text("Start session")
                }
            } else {
                val kcalBurned = training?.optDouble("kcal_burned_today") ?: 0.0
                val groupsArr = training?.optJSONArray("muscle_groups_today")
                val groups = (0 until (groupsArr?.length() ?: 0)).map { groupsArr!!.getString(it) }
                val prCount = training?.optJSONArray("prs_today")?.length() ?: 0

                Text(
                    "$setCount set${if (setCount == 1) "" else "s"} logged" +
                        if (groups.isNotEmpty()) " — ${groups.joinToString(", ")}" else "",
                )
                Text("${kcalBurned.toInt()} kcal burned today", style = MaterialTheme.typography.bodyMedium)
                if (prCount > 0) {
                    Text("🎉 $prCount PR${if (prCount > 1) "s" else ""} today", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = onNavigateToWorkout) { Text("Log another session") }
            }
        }
    }
}

@Composable
private fun SpendCard(vm: TodayViewModel, context: Context) {
    val spend = vm.todayJson?.optJSONObject("spend")
    val actual = (spend?.optDouble("actual") ?: 0.0) + vm.pendingSpend
    val goal = optionalDouble(spend, "goal")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Spend", style = MaterialTheme.typography.titleMedium)
            Text(
                Format.currency(actual) + (goal?.let { " / ${Format.currency(it)}" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (goal != null && goal > 0) {
                LinearProgressIndicator(
                    progress = (actual / goal).toFloat().coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.expenseAmount,
                    onValueChange = { vm.expenseAmount = it },
                    label = { Text("Quick-add ₹") },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { vm.logExpense(context) },
                    enabled = (vm.expenseAmount.toDoubleOrNull() ?: 0.0) > 0,
                ) { Text("Add") }
            }
            if (vm.expenseResultText.isNotBlank()) {
                Text(vm.expenseResultText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TasksCard(vm: TodayViewModel, context: Context) {
    val openTasks = vm.todayJson?.optJSONArray("open_tasks")
    val count = openTasks?.length() ?: 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tasks", style = MaterialTheme.typography.titleMedium)
            val visible = (0 until count).map { openTasks!!.getJSONObject(it) }
                .filterNot { it.optInt("id") in vm.completingTaskIds }
            if (visible.isEmpty()) {
                Text("Nothing open", style = MaterialTheme.typography.bodyMedium)
            } else {
                visible.forEach { task ->
                    val taskId = task.optInt("id")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = false, onCheckedChange = { vm.completeTask(context, taskId) })
                        Text(task.optString("title"), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun NudgesRow(todayJson: JSONObject?) {
    val nudges = todayJson?.optJSONArray("nudges")
    if (nudges == null || nudges.length() == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until nudges.length()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    nudges.getString(i),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun NutritionCard(vm: TodayViewModel, context: Context) {
    val nutrition = vm.todayJson?.optJSONObject("nutrition")
    val kcal = nutrition?.optJSONObject("kcal")
    val protein = nutrition?.optJSONObject("protein_g")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                KcalRing(
                    actual = kcal?.optDouble("actual") ?: 0.0,
                    goal = optionalDouble(kcal, "goal"),
                )
                ProteinBar(
                    actual = protein?.optDouble("actual") ?: 0.0,
                    goal = optionalDouble(protein, "goal"),
                    modifier = Modifier.weight(1f),
                )
            }
            SlotRow(vm, nutrition, context)
            if (vm.logResultText.isNotBlank()) {
                Text(vm.logResultText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun KcalRing(actual: Double, goal: Double?, modifier: Modifier = Modifier) {
    val fraction = if (goal != null && goal > 0) (actual / goal).toFloat().coerceIn(0f, 1f) else 0f
    Box(modifier = modifier.size(72.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = fraction,
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 6.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Format.kcal(actual), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            if (goal != null) {
                Text("/ ${Format.kcal(goal)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ProteinBar(actual: Double, goal: Double?, modifier: Modifier = Modifier) {
    val fraction = if (goal != null && goal > 0) (actual / goal).toFloat().coerceIn(0f, 1f) else 0f
    Column(modifier = modifier) {
        Text(
            "Protein: ${Format.grams(actual)}" + (goal?.let { " / ${Format.grams(it)}" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
        )
        LinearProgressIndicator(
            progress = fraction,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun SlotRow(vm: TodayViewModel, nutrition: JSONObject?, context: Context) {
    val serverLogged = remember(nutrition) {
        val arr = nutrition?.optJSONArray("meal_slots_logged")
        if (arr == null) emptySet() else (0 until arr.length()).map { arr.getString(it) }.toSet()
    }
    val loggedSlots = serverLogged + vm.locallyLoggedSlots
    val usualBySlot = nutrition?.optJSONObject("usual")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        MEAL_SLOTS.forEach { slot ->
            val usual = usualBySlot?.optJSONObject(slot)
            val timesLogged = usual?.optInt("times_logged", 0) ?: 0
            SlotChip(
                label = SLOT_LABELS[slot] ?: slot,
                logged = slot in loggedSlots,
                hasUsual = timesLogged > 0,
                onLogUsual = { if (usual != null) vm.logUsual(context, slot, usual) },
                onEdit = { vm.startEditingSlot(context, slot) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SlotChip(
    label: String,
    logged: Boolean,
    hasUsual: Boolean,
    onLogUsual: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (logged) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable(enabled = !logged) { if (hasUsual) onLogUsual() else onEdit() },
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                when {
                    logged -> "✓"
                    hasUsual -> "Usual"
                    else -> "+"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MealEditor(vm: TodayViewModel, context: Context) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${vm.mealType.replaceFirstChar { it.uppercase() }} suggestion",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row {
                    TextButton(onClick = { vm.refreshMeal(context) }, enabled = !vm.mealRefreshing) {
                        Text(if (vm.mealRefreshing) "Refreshing..." else "🔄 Refresh")
                    }
                    TextButton(onClick = { vm.editingSlot = null }) { Text("✕") }
                }
            }

            if (vm.mealLoading) {
                Text("Loading...")
            } else {
                vm.mealNames.forEachIndexed { idx, name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(name, modifier = Modifier.weight(1f))
                        OutlinedTextField(
                            value = vm.mealGrams.getOrElse(idx) { "" },
                            onValueChange = { vm.updateGram(idx, it) },
                            label = { Text("g") },
                            modifier = Modifier.width(90.dp)
                        )
                    }
                }
                if (vm.mealTotalText.isNotBlank()) {
                    Text(vm.mealTotalText, style = MaterialTheme.typography.bodyMedium)
                }
                if (vm.mealNames.isNotEmpty()) {
                    Button(onClick = { vm.logMeal(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Log this meal")
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(vm: TodayViewModel) {
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> vm.chatImageUri = uri }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.chatImageUri?.let { uri ->
                Box(contentAlignment = Alignment.TopEnd) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Attached photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    IconButton(
                        onClick = { vm.chatImageUri = null },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                            Text(
                                "✕",
                                modifier = Modifier.padding(4.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.chatInput,
                    onValueChange = { vm.chatInput = it },
                    placeholder = { Text("Log anything...") },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { pickImage.launch("image/*") },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Text("📷")
                }
                IconButton(
                    onClick = {
                        val uri = vm.chatImageUri
                        val bytes = uri?.let {
                            context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                        }
                        val mimeType = uri?.let { context.contentResolver.getType(it) }
                        vm.sendChat(context, bytes, mimeType)
                    },
                    enabled = !vm.chatLoading && (vm.chatInput.isNotBlank() || vm.chatImageUri != null),
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (!vm.chatLoading && (vm.chatInput.isNotBlank() || vm.chatImageUri != null)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                ) {
                    if (vm.chatLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("➤", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatResultCard(vm: TodayViewModel) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vm.chatResultText)
            if (vm.chatEventIds.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val allUndone = vm.chatEventIds.all { it.second in vm.chatUndone }
                    if (allUndone) {
                        Text("Undone", style = MaterialTheme.typography.bodySmall)
                    } else {
                        TextButton(onClick = { vm.editChatResult(context) }) { Text("Edit") }
                        vm.chatEventIds.forEach { (_, eventId) ->
                            if (eventId !in vm.chatUndone) {
                                TextButton(onClick = { vm.undoChatEvent(context, eventId) }) {
                                    Text("Undo")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
