package com.blake.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Placeholder — trends/streaks land in ROADMAP-NEXTGEN.md's P6. */
@Composable
fun InsightsScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Insights coming soon", style = MaterialTheme.typography.bodyLarge)
    }
}
