package com.blake.app.ui.pop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A darker shade of this color, used for the "shadow" layer beneath a Pop surface. */
fun Color.popShadow(factor: Float = 0.6f): Color =
    copy(red = red * factor, green = green * factor, blue = blue * factor)

private val PRESS_DEPTH = 6.dp

/**
 * NeoPop-style 3D button: a solid-color front surface sits [PRESS_DEPTH] above a darker
 * "shadow" surface of the same shape; pressing animates the front down to meet it, then back
 * up on release — the chunky, beveled "pop" look, built natively in Compose (the real NeoPop
 * library is XML-View-based and not usable here without AndroidView bridging).
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
    var pressed by remember { mutableStateOf(false) }
    val offset by animateDpAsState(if (pressed) 0.dp else PRESS_DEPTH, label = "pop-offset")
    val effectiveColor = if (enabled) color else color.copy(alpha = 0.4f)

    Box(modifier = modifier.height(56.dp + PRESS_DEPTH)) {
        Surface(
            shape = shape,
            color = effectiveColor.popShadow(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .align(Alignment.BottomCenter),
        ) {}
        Surface(
            shape = shape,
            color = effectiveColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .offset(y = offset)
                .align(Alignment.TopCenter)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { onClick() },
                    )
                },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
            }
        }
    }
}

/**
 * NeoPop-style selectable tile (used for Gym/Home and muscle-group pickers). Same layered
 * "pop" look as [PopButton], plus a tilt-free selected state (filled vs. outlined shadow).
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
    var pressed by remember { mutableStateOf(false) }
    val offset by animateDpAsState(if (pressed) 0.dp else PRESS_DEPTH, label = "pop-tile-offset")
    val baseColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(modifier = modifier.height(height + PRESS_DEPTH)) {
        Surface(
            shape = shape,
            color = baseColor.popShadow(),
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .align(Alignment.BottomCenter),
        ) {}
        Surface(
            shape = shape,
            color = baseColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = offset)
                .align(Alignment.TopCenter)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { onClick() },
                    )
                },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}
