package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CheetahDarkColorScheme = darkColorScheme(
    primary = CheetahCyan,
    onPrimary = CheetahObsidian,
    primaryContainer = CheetahCyanSoft,
    onPrimaryContainer = Color.White,
    secondary = CheetahAmber,
    onSecondary = CheetahObsidian,
    tertiary = CheetahEmerald,
    onTertiary = CheetahObsidian,
    error = CheetahCrimson,
    onError = Color.White,
    background = CheetahObsidian,
    onBackground = CheetahTextPrimary,
    surface = CheetahSlate,
    onSurface = CheetahTextPrimary,
    surfaceVariant = CheetahCard,
    onSurfaceVariant = CheetahTextSecondary,
    outline = CheetahBorder
)

@Composable
fun CheetahAssistantTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CheetahDarkColorScheme,
        typography = CheetahTypography,
        content = content
    )
}
