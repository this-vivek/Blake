package com.blake.app.ui.pop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A darker shade of this color, used for the "shadow" layer beneath a Pop surface. */
fun Color.popShadow(factor: Float = 0.45f): Color =
    copy(red = red * factor, green = green * factor, blue = blue * factor)

private val PRESS_DEPTH = 10.dp
private val OUTLINE = Color.Black.copy(alpha = 0.85f)

/**
 * NeoPop-style 3D button: a solid-color front surface sits [PRESS_DEPTH] above a darker
 * "shadow" surface of the same shape, both outlined in near-black for the bold "pop art"
 * edge; pressing animates the front down to meet the shadow, then back up on release. Built
 * natively in Compose (the real NeoPop library is XML-View-based and not usable here without
 * AndroidView bridging) using the standard clickable+interactionSource press-state pattern,
 * not a hand-rolled gesture detector.
 */
@Composable
fun PopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    shape: Shape = RoundedCornerShape(16.dp),
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offset by animateDpAsState(if (isPressed) 0.dp else PRESS_DEPTH, label = "pop-offset")
    val effectiveColor = if (enabled) color else color.copy(alpha = 0.4f)

    Box(modifier = modifier.height(56.dp + PRESS_DEPTH)) {
        Surface(
            shape = shape,
            color = effectiveColor.popShadow(),
            border = BorderStroke(2.dp, OUTLINE),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .align(Alignment.BottomCenter),
        ) {}
        Surface(
            shape = shape,
            color = effectiveColor,
            border = BorderStroke(2.dp, OUTLINE),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .offset(y = offset)
                .align(Alignment.TopCenter)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
            }
        }
    }
}

/**
 * NeoPop-style selectable tile (used for Gym/Home and muscle-group pickers). Same layered
 * "pop" look as [PopButton].
 */
@Composable
fun PopTile(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offset by animateDpAsState(if (isPressed) 0.dp else PRESS_DEPTH, label = "pop-tile-offset")
    val baseColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(modifier = modifier.height(height + PRESS_DEPTH)) {
        Surface(
            shape = shape,
            color = baseColor.popShadow(),
            border = BorderStroke(2.dp, OUTLINE),
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .align(Alignment.BottomCenter),
        ) {}
        Surface(
            shape = shape,
            color = baseColor,
            border = BorderStroke(2.dp, OUTLINE),
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = offset)
                .align(Alignment.TopCenter)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            val contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
            }
        }
    }
}
