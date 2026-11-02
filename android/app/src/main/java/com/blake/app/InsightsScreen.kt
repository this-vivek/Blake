package com.blake.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blake.app.ui.theme.Spacing

/** Placeholder: trends and streaks land in ROADMAP-NEXTGEN.md's P6. */
@Composable
fun InsightsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("Insights", style = MaterialTheme.typography.titleLarge)
        Text(
            "Trends, streaks and your weekly review are coming next.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
