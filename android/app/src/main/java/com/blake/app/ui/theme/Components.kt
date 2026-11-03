package com.blake.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

private val PillShape = RoundedCornerShape(percent = 50)
private val RowShape = RoundedCornerShape(14.dp)

/**
 * The one press effect for everything tappable: the element dips with a springy bounce and a
 * soft light washes over it, clipped to its own shape (never a square). The default Material
 * ripple is switched off wherever this is used, because it drew a square box on some shapes.
 */
private fun Modifier.fancyPress(
    source: MutableInteractionSource,
    shape: Shape,
    pressedScale: Float,
    highlight: Color,
): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        // Soft and a little bouncy on release: that rebound is what makes it feel physical.
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "press-scale",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = if (pressed) 70 else 280),
        label = "press-glow",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            drawContent()
            if (glow > 0.01f) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = highlight.copy(alpha = highlight.alpha * glow),
                )
            }
        }
}

/** [onClick] wrapped with a light haptic tick. */
@Composable
private fun withTick(onClick: () -> Unit): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
}

private val LightGlow = Color.White.copy(alpha = 0.20f)

/** A clickable with the same press effect as the buttons, for rows, tabs and icon buttons. */
fun Modifier.tappable(
    shape: Shape = RowShape,
    pressedScale: Float = 0.94f,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val tick = withTick(onClick)
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    this
        .fancyPress(source, shape, pressedScale, tint)
        .clip(shape)
        .clickable(interactionSource = source, indication = null, onClick = tick)
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
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
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

/** Shared body of the pill buttons. A disabled one fades and ignores taps. */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    container: Color,
    content: Color,
    highlight: Color,
    border: BorderStroke?,
) {
    val source = remember { MutableInteractionSource() }
    val tick = withTick(onClick)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .fancyPress(source, PillShape, 0.95f, highlight)
            .clip(PillShape)
            .background(container)
            .let { if (border != null) it.border(border, PillShape) else it }
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = tick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** A screen has exactly one of these. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = PillButton(
    text, onClick, modifier, enabled,
    container = MaterialTheme.colorScheme.primary,
    content = MaterialTheme.colorScheme.onPrimary,
    highlight = LightGlow,
    border = null,
)

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = PillButton(
    text, onClick, modifier, enabled,
    container = MaterialTheme.colorScheme.surfaceVariant,
    content = MaterialTheme.colorScheme.onSurface,
    highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
)

/** Accent text, no fill: Undo, Edit, Cancel. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val tick = withTick(onClick)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .fancyPress(source, PillShape, 0.92f, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            .clip(PillShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = tick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** Tapping a selected chip deselects it; the caller decides what that means. */
@Composable
fun BlakeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val tick = withTick(onClick)
    val container by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(180),
        label = "chip-fill",
    )
    val glow = if (selected) LightGlow else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .fancyPress(source, PillShape, 0.92f, glow)
            .clip(PillShape)
            .background(container)
            .let { if (selected) it else it.border(1.dp, MaterialTheme.colorScheme.outline, PillShape) }
            .clickable(interactionSource = source, indication = null, onClick = tick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 6dp progress bar: accent fill on a divider track. */
@Composable
fun ThinBar(fraction: Float, modifier: Modifier = Modifier, fill: Color = MaterialTheme.colorScheme.primary) {
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
