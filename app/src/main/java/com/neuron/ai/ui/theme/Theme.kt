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

private val Accent500 = Color(0xFF4F46E5) // indigo — brand primary
private val Accent600 = Color(0xFF4338CA)
private val Accent100 = Color(0xFFE0E7FF)
private val Accent200 = Color(0xFFA5B4FC)
private val Accent800 = Color(0xFF3730A3)
private val Accent900 = Color(0xFF1E1B4B)

private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceDimLight = Color(0xFFF6F7F9)
private val BorderLight = Color(0xFFE5E7EB)

// Dark: three DISTINCT elevation steps — background < surface < surfaceVariant —
// so cards, sheets and code blocks never melt into the window behind them.
private val BgDark = Color(0xFF0B0D10)        // window (lowest)
private val SurfaceDark = Color(0xFF111318)   // cards/sheets
private val SurfaceVariantDark = Color(0xFF1A1F27) // tool cards, code, chips
private val BorderDark = Color(0xFF2A2F37)
private val BorderDarkSubtle = Color(0xFF232830)

private val LightColors = lightColorScheme(
    primary = Accent500,
    onPrimary = Color.White,
    primaryContainer = Accent100,
    onPrimaryContainer = Accent600,
    inversePrimary = Accent200,
    secondary = Ink600,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFF0F766E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF134E4A),
    background = SurfaceLight,
    onBackground = Ink900,
    surface = SurfaceLight,
    onSurface = Ink900,
    surfaceVariant = SurfaceDimLight,
    onSurfaceVariant = Ink600,
    surfaceTint = Accent500,
    inverseSurface = Color(0xFF1F242B),
    inverseOnSurface = Color(0xFFF1F3F6),
    outline = BorderLight,
    outlineVariant = BorderLight,
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    scrim = Color.Black
)

private val DarkColors = darkColorScheme(
    primary = Accent200,
    onPrimary = Accent900,
    primaryContainer = Accent800,
    onPrimaryContainer = Accent100,
    inversePrimary = Accent500,
    secondary = Color(0xFFB7BCC7),
    onSecondary = Color(0xFF1F242B),
    secondaryContainer = Color(0xFF2A2F37),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFF5EEAD4),
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = BgDark,
    onBackground = Color(0xFFE8EAEE),
    surface = SurfaceDark,
    onSurface = Color(0xFFE8EAEE),
    // THE fix: surfaceVariant is now clearly lighter than surface, so tool
    // cards / code blocks / sheets read as raised surfaces, not black voids.
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFF9AA1AC),
    surfaceTint = Accent200,
    inverseSurface = Color(0xFFE8EAEE),
    inverseOnSurface = Color(0xFF1F242B),
    outline = BorderDark,
    outlineVariant = BorderDarkSubtle,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    scrim = Color.Black
)

/**
 * App theme. Light is the reference design; dark is a first-class twin with
 * proper tonal elevation — every container role is explicitly set so no
 * Material default (purple) ever leaks through.
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
