package com.neuron.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---- Palette: calm, professional, light-first -------------------------------
private val Ink900 = Color(0xFF111827)
private val Ink600 = Color(0xFF4B5563)
private val Ink400 = Color(0xFF9CA3AF)

private val Accent500 = Color(0xFF4F46E5) // indigo — brand primary
private val Accent600 = Color(0xFF4338CA)
private val Accent100 = Color(0xFFE0E7FF)

private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceDimLight = Color(0xFFF6F7F9)
private val BorderLight = Color(0xFFE5E7EB)

private val SurfaceDark = Color(0xFF111318)
private val SurfaceDimDark = Color(0xFF0B0D10)
private val BorderDark = Color(0xFF2A2F37)

private val LightColors = lightColorScheme(
    primary = Accent500,
    onPrimary = Color.White,
    primaryContainer = Accent100,
    onPrimaryContainer = Accent600,
    secondary = Ink600,
    onSecondary = Color.White,
    background = SurfaceLight,
    onBackground = Ink900,
    surface = SurfaceLight,
    onSurface = Ink900,
    surfaceVariant = SurfaceDimLight,
    onSurfaceVariant = Ink600,
    outline = BorderLight,
    outlineVariant = BorderLight,
    error = Color(0xFFDC2626)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF818CF8),
    onPrimary = Color(0xFF111318),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Accent100,
    secondary = Color(0xFFB7BCC7),
    onSecondary = SurfaceDark,
    background = SurfaceDimDark,
    onBackground = Color(0xFFE8EAEE),
    surface = SurfaceDark,
    onSurface = Color(0xFFE8EAEE),
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = Color(0xFF9AA1AC),
    outline = BorderDark,
    outlineVariant = BorderDark,
    error = Color(0xFFF87171)
)

/**
 * App theme. Light-first: even in SYSTEM/DARK modes the light scheme remains
 * the reference design; dark is a first-class but secondary experience.
 */
@Composable
fun NeuronTheme(
    darkMode: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkMode) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = NeuronTypography,
        shapes = NeuronShapes,
        content = content
    )
}
