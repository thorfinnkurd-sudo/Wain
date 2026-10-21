package com.wain.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Wain design foundation.
 *
 * Keep brand and semantic colors centralized here so screens and map controls
 * can share one consistent visual language. MapLibre style colors are configured
 * separately in the map style/controller; these values theme Compose UI only.
 */

// Brand palette
val Lapis = Color(0xFF245BDB)
val Gold = Color(0xFFFFC857)
val WainGreen = Color(0xFF16A765)
val WainRed = Color(0xFFE5484D)

// Shared semantic colors for polished controls, status chips and navigation UI.
val WainBlueBright = Color(0xFF3478F6)
val WainBlueDeep = Color(0xFF153C9B)
val WainCyan = Color(0xFF37B9D6)
val WainAmber = Color(0xFFF4A62A)

val WainLightCanvas = Color(0xFFF3F6FC)
val WainLightSurface = Color(0xFFFFFFFF)
val WainLightSurfaceAlt = Color(0xFFEAF0FA)
val WainLightText = Color(0xFF172033)
val WainLightTextMuted = Color(0xFF667085)
val WainLightBorder = Color(0xFFD9E1EF)

val WainDarkCanvas = Color(0xFF0B1220)
val WainDarkSurface = Color(0xFF131D2E)
val WainDarkSurfaceAlt = Color(0xFF1C2940)
val WainDarkText = Color(0xFFF3F6FC)
val WainDarkTextMuted = Color(0xFFB2BED1)
val WainDarkBorder = Color(0xFF34435B)

// Light theme: clean, high-contrast surfaces with a confident navigation blue.
private val Light = lightColorScheme(
    primary = Lapis,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF102E70),

    secondary = Color(0xFF825300),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE7B3),
    onSecondaryContainer = Color(0xFF352100),

    tertiary = WainGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7F7E5),
    onTertiaryContainer = Color(0xFF064B2A),

    background = WainLightCanvas,
    onBackground = WainLightText,

    surface = WainLightSurface,
    onSurface = WainLightText,

    surfaceVariant = WainLightSurfaceAlt,
    onSurfaceVariant = Color(0xFF47536A),

    outline = Color(0xFF8793A8),
    outlineVariant = WainLightBorder,

    error = WainRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD8),
    onErrorContainer = Color(0xFF690005),
)

// Dark theme: deep navy surfaces, readable text and restrained bright accents.
private val Dark = darkColorScheme(
    primary = Color(0xFFAFC7FF),
    onPrimary = Color(0xFF102F70),
    primaryContainer = Color(0xFF244B9B),
    onPrimaryContainer = Color(0xFFDCE7FF),

    secondary = Gold,
    onSecondary = Color(0xFF352100),
    secondaryContainer = Color(0xFF624700),
    onSecondaryContainer = Color(0xFFFFE7B3),

    tertiary = Color(0xFF73DCA5),
    onTertiary = Color(0xFF00391D),
    tertiaryContainer = Color(0xFF075332),
    onTertiaryContainer = Color(0xFFB8F5D1),

    background = WainDarkCanvas,
    onBackground = WainDarkText,

    surface = WainDarkSurface,
    onSurface = WainDarkText,

    surfaceVariant = WainDarkSurfaceAlt,
    onSurfaceVariant = WainDarkTextMuted,

    outline = Color(0xFF8D9BB2),
    outlineVariant = WainDarkBorder,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

// Carefully tuned Material typography. No custom font family is forced here,
// so Arabic, Sorani Kurdish and English continue to use Android's font fallback.
private val WainTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
)

@Composable
fun WainTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = WainTypography,
        content = content,
    )
}
