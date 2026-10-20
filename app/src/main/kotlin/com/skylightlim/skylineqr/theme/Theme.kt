package com.skylightlim.skylineqr.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** For the panel after dark, and in a storm. */
val SkylineDark =
  darkColorScheme(
    primary = Color(0xFF9FD0FF),
    onPrimary = Color(0xFF06263F),
    surface = Color(0xFF12151C),
    onSurface = Color(0xFFE9EBF1),
    surfaceVariant = Color(0xFF1D2230),
    onSurfaceVariant = Color(0xFFB7BFD0),
    secondaryContainer = Color(0xFF27334A),
    onSecondaryContainer = Color(0xFFD9E2F5),
    outline = Color(0xFF5A6478),
    background = Color(0xFF0B0E14),
    error = Color(0xFFFF8A8A),
  )

/** For the panel by day, when the city behind it is bright. */
val SkylineLight =
  lightColorScheme(
    primary = Color(0xFF1F5C99),
    onPrimary = Color(0xFFFFFFFF),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF1C1F26),
    surfaceVariant = Color(0xFFEDEAE3),
    onSurfaceVariant = Color(0xFF4F5563),
    secondaryContainer = Color(0xFFDDE6F3),
    onSecondaryContainer = Color(0xFF122338),
    outline = Color(0xFF9A9FAA),
    background = Color(0xFFF6F4EF),
    error = Color(0xFFB3261E),
  )

@Composable
fun SkylineQRTheme(dark: Boolean = true, content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (dark) SkylineDark else SkylineLight, typography = Typography, content = content)
}
