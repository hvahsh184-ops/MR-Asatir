package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TacticalRadarColorScheme = darkColorScheme(
    primary = RadarEmerald,
    onPrimary = OnRadarEmerald,
    primaryContainer = RadarEmeraldContainer,
    onPrimaryContainer = RadarEmerald,
    secondary = TelemetryCyan,
    onSecondary = OnTelemetryCyan,
    secondaryContainer = TelemetryCyanContainer,
    onSecondaryContainer = TelemetryCyan,
    tertiary = AnomalyAmber,
    onTertiary = OnAnomalyAmber,
    tertiaryContainer = AnomalyAmberContainer,
    onTertiaryContainer = AnomalyAmber,
    error = TargetLockCrimson,
    onError = OnTargetLockCrimson,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = DeepSlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedSlateVariant,
    onSurfaceVariant = TextSecondary,
    outline = OutlineSlate,
    outlineVariant = OutlineSlate
)

@Composable
fun RadarSurfaceTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TacticalRadarColorScheme,
        typography = AppTypography,
        content = content
    )
}
