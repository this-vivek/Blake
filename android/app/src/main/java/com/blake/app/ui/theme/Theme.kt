package com.blake.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blake.app.R

// Tokens from the Blake Design System (dark first). Wallpaper-derived dynamic colour is off
// on purpose: the app should look the same on every phone.

@Immutable
class BlakeColors(
    val divider: Color,
    val success: Color,
    val warning: Color,
    val scrim: Color,
)

private val DarkExtra = BlakeColors(
    divider = Color(0xFF26262B),
    success = Color(0xFF7FD1A8),
    warning = Color(0xFFE7A04A),
    scrim = Color(0xB3000000),
)
private val LightExtra = BlakeColors(
    divider = Color(0xFFE3E3DF),
    success = Color(0xFF1F7A52),
    warning = Color(0xFF9A5B00),
    scrim = Color(0x660E0E10),
)

val LocalBlakeColors = staticCompositionLocalOf { DarkExtra }

private val DarkScheme = darkColorScheme(
    background = Color(0xFF0B0B0D),
    onBackground = Color(0xFFF2F2F0),
    surface = Color(0xFF141417),
    onSurface = Color(0xFFF2F2F0),
    surfaceVariant = Color(0xFF1C1C20),
    onSurfaceVariant = Color(0xFFA0A0A8),
    surfaceContainer = Color(0xFF141417),
    surfaceContainerHigh = Color(0xFF1C1C20),
    primary = Color(0xFFE6C07B),
    onPrimary = Color(0xFF16120A),
    primaryContainer = Color(0xFF1C1C20),
    onPrimaryContainer = Color(0xFFF2F2F0),
    secondaryContainer = Color(0xFF1C1C20),
    onSecondaryContainer = Color(0xFFF2F2F0),
    tertiaryContainer = Color(0xFF1C1C20),
    onTertiaryContainer = Color(0xFFF2F2F0),
    outline = Color(0xFF74747E),
    outlineVariant = Color(0xFF26262B),
    error = Color(0xFFF08A82),
    onError = Color(0xFF16120A),
)

private val LightScheme = lightColorScheme(
    background = Color(0xFFF6F6F4),
    onBackground = Color(0xFF0E0E10),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0E0E10),
    surfaceVariant = Color(0xFFEDEDEA),
    onSurfaceVariant = Color(0xFF5B5B63),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDEDEA),
    primary = Color(0xFF8A5A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEDEA),
    onPrimaryContainer = Color(0xFF0E0E10),
    secondaryContainer = Color(0xFFEDEDEA),
    onSecondaryContainer = Color(0xFF0E0E10),
    tertiaryContainer = Color(0xFFEDEDEA),
    onTertiaryContainer = Color(0xFF0E0E10),
    outline = Color(0xFF7F7F89),
    outlineVariant = Color(0xFFE3E3DF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    val sm = 10.dp
    val md = 16.dp
    val lg = 24.dp
}

@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val SoraFamily = FontFamily(
    variableFont(R.font.sora, 400),
    variableFont(R.font.sora, 600),
)
val ManropeFamily = FontFamily(
    variableFont(R.font.manrope, 400),
    variableFont(R.font.manrope, 600),
)

val BlakeTypography = Typography(
    // Hero and card figures
    displayLarge = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-1.3).sp),
    displayMedium = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.9).sp),
    displaySmall = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    // Text
    titleLarge = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

val MaterialTheme.blake: BlakeColors
    @Composable get() = LocalBlakeColors.current

@Composable
fun BlakeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBlakeColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = BlakeTypography,
            content = content,
        )
    }
}
