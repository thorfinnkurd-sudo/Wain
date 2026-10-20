package com.wain.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Lapis = Color(0xFF17408B)
val Gold = Color(0xFFF2B01E)

private val Light = lightColorScheme(
    primary = Lapis, onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF), onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF7A5900), secondaryContainer = Gold, onSecondaryContainer = Color(0xFF241A00),
    background = Color(0xFFF8F9FC), surface = Color.White, error = Color(0xFFB3261E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFADC6FF), onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494), onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Gold, secondaryContainer = Color(0xFF5B4300), onSecondaryContainer = Color(0xFFFFDEA0),
    background = Color(0xFF111318), surface = Color(0xFF1B1D24), error = Color(0xFFF2B8B5),
)

@Composable
fun WainTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
