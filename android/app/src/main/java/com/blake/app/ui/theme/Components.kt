package com.blake.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private val PillShape = RoundedCornerShape(percent = 50)

/** Every tappable thing in the app feels the same: it dips and dims while pressed, springs
 * back, and gives a light tick on tap. */
private fun Modifier.pressFeedback(source: MutableInteractionSource, pressedScale: Float = 0.95f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press-scale",
    )
    val dim by animateFloatAsState(if (pressed) 0.8f else 1f, label = "press-dim")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = dim
    }
}

/** A clickable with the same press feedback as the buttons, for rows, tabs and icon buttons. */
fun Modifier.tappable(pressedScale: Float = 0.94f, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val tick = withTick(onClick)
    pressFeedback(source, pressedScale)
        .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = tick)
}

/** [onClick] wrapped with a light haptic tick. */
@Composable
private fun withTick(onClick: () -> Unit): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
}

/** The one container: surface on background, radius-lg, 16dp padding. */
@Composable
fun BlakeCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.md),
            content = content,
        )
    }
}

/** Uppercase tracked label used at the top of cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A screen has exactly one of these. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val tick = withTick(onClick)
    Button(
        onClick = tick,
        interactionSource = source,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).pressFeedback(source, 0.96f),
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val tick = withTick(onClick)
    Button(
        onClick = tick,
        interactionSource = source,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).pressFeedback(source, 0.96f),
        shape = PillShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Accent text, no fill: Undo, Edit, Cancel. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val tick = withTick(onClick)
    TextButton(
        onClick = tick,
        interactionSource = source,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).pressFeedback(source, 0.92f),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** Tapping a selected chip deselects it; the caller decides what that means. */
@Composable
fun BlakeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val tick = withTick(onClick)
    Surface(
        modifier = modifier.heightIn(min = 40.dp).pressFeedback(source, 0.93f).clip(PillShape)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = tick),
        shape = PillShape,
        color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(text.uppercase(), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 6dp progress bar: accent fill on a divider track. */
@Composable
fun ThinBar(fraction: Float, modifier: Modifier = Modifier, fill: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.blake.divider)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(CircleShape)
                .background(fill)
        )
    }
}
