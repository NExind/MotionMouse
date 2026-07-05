package com.motionmouse.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Motion Mouse only ships a dark theme.
 *
 * Rationale:
 *   This is a peripheral controller used at a desk, often in low-light.
 *   A light theme would cause eye strain in dark environments.
 *   Dark-only also means half the theming complexity — simpler,
 *   easier to keep consistent, and premium-feeling.
 *
 *   If light theme is ever added, it belongs in a future version.
 */
private val DarkColorScheme = darkColorScheme(
    primary           = MotionBlue,
    onPrimary         = Color.White,
    primaryContainer  = MotionBlueDim,
    onPrimaryContainer = MotionBlueLight,

    secondary         = Connected,
    onSecondary       = Color.Black,

    background        = Surface0,
    onBackground      = TextPrimary,

    surface           = Surface1,
    onSurface         = TextPrimary,

    surfaceVariant    = Surface2,
    onSurfaceVariant  = TextSecondary,

    error             = ErrorRed,
    onError           = Color.White,

    outline           = Surface3,
    outlineVariant    = TextTertiary
)

@Composable
fun MotionMouseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = MotionMouseTypography,
        content = content
    )
}
