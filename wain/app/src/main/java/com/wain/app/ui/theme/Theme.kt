
package com.wain.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Wain brand colors
val Lapis = Color(0xFF1769FF)
val Gold = Color(0xFFFFC247)
val WainGreen = Color(0xFF20B86A)
val WainRed = Color(0xFFE5484D)

// Light theme
private val Light = lightColorScheme(
    primary = Lapis,
    onPrimary = Color.White,

    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF082B68),

    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4A8),
    onSecondaryContainer = Color(0xFF352100),

    tertiary = WainGreen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD4F8E4),
    onTertiaryContainer = Color(0xFF064B2A),

    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF191D26),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191D26),

    surfaceVariant = Color(0xFFE9EDF5),
    onSurfaceVariant = Color(0xFF454B59),

    outline = Color(0xFF747D8D),
    outlineVariant = Color(0xFFD8DEEA),

    error = WainRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD8),
    onErrorContainer = Color(0xFF690005),
)

// Dark theme
private val Dark = darkColorScheme(
    primary = Color(0xFFADC8FF),
    onPrimary = Color(0xFF002F6C),

    primaryContainer = Color(0xFF164A9B),
    onPrimaryContainer = Color(0xFFDCE8FF),

    secondary = Gold,
    onSecondary = Color(0xFF352100),
    secondaryContainer = Color(0xFF654800),
    onSecondaryContainer = Color(0xFFFFE4A8),

    tertiary = Color(0xFF70DFA3),
    onTertiary = Color(0xFF00391D),
    tertiaryContainer = Color(0xFF075332),
    onTertiaryContainer = Color(0xFFB8F5D1),

    background = Color(0xFF10141C),
    onBackground = Color(0xFFE2E6F0),

    surface = Color(0xFF191E28),
    onSurface = Color(0xFFE2E6F0),

    surfaceVariant = Color(0xFF292F3B),
    onSurfaceVariant = Color(0xFFC1C8D6),

    outline = Color(0xFF8993A5),
    outlineVariant = Color(0xFF3B4352),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun WainTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        content = content,
    )
}
