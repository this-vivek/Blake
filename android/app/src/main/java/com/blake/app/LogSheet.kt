package com.blake.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.blake.app.ui.theme.*
import java.io.File

/** The "+" in the nav bar opens this: text, a photo (camera or gallery), one Send button.
 * Everything is saved the moment it's sent; the result stays below with Edit / Undo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSheet(vm: TodayViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.chatImageUri = uri
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.chatImageUri = cameraUri
    }

    val canSend = !vm.chatLoading && (vm.chatInput.isNotBlank() || vm.chatImageUri != null)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.blake.scrim,
        shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
    ) {
        // Fixed height: if the content height changed (e.g. the result card disappearing on
        // Edit), the sheet re-settled and could dismiss itself. The content scrolls instead.
        Column(
            modifier = Modifier
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Log anything", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Meal, set, spend or task",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            vm.chatImageUri?.let { uri ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Attached photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(Radius.sm)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Photo attached", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Saved as soon as you send",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { vm.chatImageUri = null },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Icon(
                            BlakeIcons.Close,
                            contentDescription = "Remove photo",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = vm.chatInput,
                onValueChange = { vm.chatInput = it },
                placeholder = { Text("2 boiled eggs with poha") },
                minLines = 2,
                shape = RoundedCornerShape(Radius.md),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                SecondaryButton(
                    text = "Camera",
                    onClick = {
                        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        cameraUri = uri
                        takePicture.launch(uri)
                    },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Gallery",
                    onClick = { pickImage.launch("image/*") },
                    modifier = Modifier.weight(1f),
                )
            }

            PrimaryButton(
                text = if (vm.chatLoading) "Sending..." else "Send",
                enabled = canSend,
                onClick = {
                    val uri = vm.chatImageUri
                    val bytes = uri?.let {
                        context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() }
                    }
                    val mimeType = uri?.let { context.contentResolver.getType(it) }
                    vm.sendChat(context, bytes, mimeType)
                },
            )

            if (vm.chatResultText.isNotBlank()) {
                LogResult(vm, onDone = onDismiss)
            }
        }
    }
}

@Composable
private fun LogResult(vm: TodayViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    BlakeCard {
        Text(vm.chatResultText, style = MaterialTheme.typography.bodyLarge)

        if (vm.chatMealId != null && vm.chatMealId !in vm.chatUndone) {
            vm.chatMealItems.forEachIndexed { i, (name, grams) ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = grams,
                        onValueChange = { vm.setChatMealGrams(i, it) },
                        suffix = { Text("g") },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.sm),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(112.dp),
                    )
                }
            }
            QuietButton("Save grams", onClick = { vm.saveChatMealEdit(context) })
        }

        if (vm.chatEventIds.isNotEmpty()) {
            val allUndone = vm.chatEventIds.all { it.second in vm.chatUndone }
            if (allUndone) {
                Text(
                    "Undone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    QuietButton("Edit", onClick = { vm.editChatResult(context) })
                    vm.chatEventIds.forEach { (_, eventId) ->
                        if (eventId !in vm.chatUndone) {
                            QuietButton("Undo", onClick = { vm.undoChatEvent(context, eventId) })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    QuietButton("Done", onClick = onDone)
                }
            }
        }
    }
}
