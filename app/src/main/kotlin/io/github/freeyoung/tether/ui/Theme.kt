package io.github.freeyoung.tether.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// The colors of the iPhone app: the system colors of iOS, with teal as the
// accent and indigo next to it.
@Immutable
data class TetherColors(
    val teal: Color,
    val indigo: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val blue: Color,
    val background: Color,
    val card: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    val fill: Color,
)

private val Light = TetherColors(
    teal = Color(0xFF30B0C7),
    indigo = Color(0xFF5856D6),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30),
    blue = Color(0xFF007AFF),
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondary = Color(0x993C3C43),
    tertiary = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x1F767680),
)

private val Dark = TetherColors(
    teal = Color(0xFF40C8E0),
    indigo = Color(0xFF5E5CE6),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    blue = Color(0xFF0A84FF),
    background = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondary = Color(0x99EBEBF5),
    tertiary = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    fill = Color(0x3D767680),
)

val LocalTetherColors = staticCompositionLocalOf { Light }

object Tint {
    val colors: TetherColors @Composable get() = LocalTetherColors.current
}

@Composable
fun TetherTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Dark else Light
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.teal,
        onPrimary = Color.White,
        primaryContainer = c.teal.copy(alpha = 0.2f),
        onPrimaryContainer = c.label,
        secondary = c.indigo,
        background = c.background,
        onBackground = c.label,
        surface = c.card,
        onSurface = c.label,
        surfaceVariant = c.fill,
        onSurfaceVariant = c.secondary,
        surfaceContainer = c.card,
        surfaceContainerLow = c.card,
        surfaceContainerHigh = c.card,
        surfaceContainerHighest = c.card,
        surfaceContainerLowest = c.background,
        outline = c.separator,
        outlineVariant = c.separator,
        error = c.red,
    )
    CompositionLocalProvider(LocalTetherColors provides c) {
        MaterialTheme(colorScheme = scheme) {
            // Text in its own line height, as on iOS, not the 24 sp of bodyLarge.
            CompositionLocalProvider(LocalTextStyle provides TextStyle(color = c.label, fontSize = 17.sp), content)
        }
    }
}
