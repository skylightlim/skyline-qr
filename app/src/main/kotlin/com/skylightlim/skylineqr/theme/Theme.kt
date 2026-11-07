@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.skylightlim.skylineqr.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/*
 * Material 3 Expressive (2026-11-06): every colour role filled in, so no component falls back to
 * the baseline purple; the expressive motion scheme, whose springs drive the controls' shape
 * morphs; and the type scale with its emphasized styles.
 */

/** For the panel after dark, and in a storm. */
val SkylineDark =
  darkColorScheme(
    primary = Color(0xFF9FD0FF),
    onPrimary = Color(0xFF06263F),
    primaryContainer = Color(0xFF1B4A72),
    onPrimaryContainer = Color(0xFFD3E9FF),
    inversePrimary = Color(0xFF1F5C99),
    secondary = Color(0xFFB9C8DC),
    onSecondary = Color(0xFF233243),
    secondaryContainer = Color(0xFF27334A),
    onSecondaryContainer = Color(0xFFD9E2F5),
    tertiary = Color(0xFF9ED2CD),
    onTertiary = Color(0xFF003735),
    tertiaryContainer = Color(0xFF1F504D),
    onTertiaryContainer = Color(0xFFBCEBE6),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE9EBF1),
    surface = Color(0xFF12151C),
    onSurface = Color(0xFFE9EBF1),
    surfaceVariant = Color(0xFF1D2230),
    onSurfaceVariant = Color(0xFFB7BFD0),
    surfaceTint = Color(0xFF9FD0FF),
    inverseSurface = Color(0xFFE2E3E9),
    inverseOnSurface = Color(0xFF2E3037),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A93A6),
    outlineVariant = Color(0xFF3F4656),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF383C45),
    surfaceDim = Color(0xFF12151C),
    surfaceContainerLowest = Color(0xFF0C0F15),
    surfaceContainerLow = Color(0xFF171A22),
    surfaceContainer = Color(0xFF1B1F27),
    surfaceContainerHigh = Color(0xFF262A33),
    surfaceContainerHighest = Color(0xFF30353E),
  )

/** For the panel by day, when the city behind it is bright. */
val SkylineLight =
  lightColorScheme(
    primary = Color(0xFF1F5C99),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    inversePrimary = Color(0xFFA2C9FF),
    secondary = Color(0xFF4E5F75),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE6F3),
    onSecondaryContainer = Color(0xFF122338),
    tertiary = Color(0xFF2B6A67),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBE6),
    onTertiaryContainer = Color(0xFF00201E),
    background = Color(0xFFF6F4EF),
    onBackground = Color(0xFF1C1F26),
    surface = Color(0xFFFBFAF7),
    onSurface = Color(0xFF1C1F26),
    surfaceVariant = Color(0xFFEDEAE3),
    onSurfaceVariant = Color(0xFF4F5563),
    surfaceTint = Color(0xFF1F5C99),
    inverseSurface = Color(0xFF2F3138),
    inverseOnSurface = Color(0xFFF1F0F4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF767B86),
    outlineVariant = Color(0xFFC8CBD2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBFAF7),
    surfaceDim = Color(0xFFDBD9D4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3EE),
    surfaceContainer = Color(0xFFEFEDE8),
    surfaceContainerHigh = Color(0xFFE9E7E2),
    surfaceContainerHighest = Color(0xFFE3E1DC),
  )

/**
 * The scheme with its surface containers tinted by the [sky] the controls float in, as frost
 * would: a SurfaceView cannot be blurred from above, so the tint stands in for it. Every control,
 * menu and dialog takes its container from these roles.
 */
private fun ColorScheme.under(sky: Color, dark: Boolean): ColorScheme {
  val k = if (dark) 0.16f else 0.12f
  return copy(
    surfaceContainerLowest = lerp(surfaceContainerLowest, sky, k),
    surfaceContainerLow = lerp(surfaceContainerLow, sky, k),
    surfaceContainer = lerp(surfaceContainer, sky, k),
    surfaceContainerHigh = lerp(surfaceContainerHigh, sky, k),
    surfaceContainerHighest = lerp(surfaceContainerHighest, sky, k),
  )
}

/** The app's theme: [dark] after dark and in a storm, its containers tinted by [sky] if given. */
@Composable
fun SkylineQRTheme(dark: Boolean = true, sky: Color? = null, content: @Composable () -> Unit) {
  val base = if (dark) SkylineDark else SkylineLight
  val scheme = remember(base, sky, dark) { if (sky == null) base else base.under(sky, dark) }
  MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}
