package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.DetectedRadarPoint
import com.example.domain.GridCellAnalysis
import com.example.ui.RadarUiState
import com.example.ui.ScanState
import com.example.ui.components.TacticalRadarCanvas
import com.example.ui.theme.MonoTelemetryStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RadarTabContent(
    state: RadarUiState,
    onRequestLocationPermission: () -> Unit,
    onToggleStaticGpsAveraging: () -> Unit,
    onApplyAveragedToCenter: () -> Unit,
    onApplyAveragedToGcpTrue: () -> Unit,
    onUseCurrentGps: () -> Unit,
    onLatChanged: (String) -> Unit,
    onLonChanged: (String) -> Unit,
    onRadiusChanged: (Float) -> Unit,
    onRunScan: () -> Unit,
    onToggleAudio: () -> Unit,
    onToggleTtsGuidance: () -> Unit,
    onSpeakCueNow: () -> Unit,
    onCellSelected: (GridCellAnalysis) -> Unit,
    onDetectedTargetSelected: (DetectedRadarPoint, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Coordinates & Radius Input Card (Immediately visible at top!)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ورود مختصات و شعاع پردازش",
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(
                        onClick = onUseCurrentGps,
                        enabled = state.liveGps != null || state.averagedGpsLat != null,
                        modifier = Modifier.testTag("use_live_gps_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "پر کردن با GPS",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (state.averagedGpsLat != null) "GPS میانگین" else "GPS من")
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = state.latInput,
                            onValueChange = onLatChanged,
                            label = { Text("Latitude") },
                            singleLine = true,
                            textStyle = MonoTelemetryStyle,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("latitude_input")
                        )
                        OutlinedTextField(
                            value = state.lonInput,
                            onValueChange = onLonChanged,
                            label = { Text("Longitude") },
                            singleLine = true,
                            textStyle = MonoTelemetryStyle,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("longitude_input")
                        )
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "شعاع اسکن (CFAR + چندطیفی + DEM)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${state.radiusMeters.roundToInt()} متر",
                            style = MonoTelemetryStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = state.radiusMeters,
                        onValueChange = onRadiusChanged,
                        valueRange = 100f..5000f,
                        steps = 48,
                        modifier = Modifier.testTag("radius_slider")
                    )
                }

                Button(
                    onClick = onRunScan,
                    enabled = state.scanState !is ScanState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("run_scan_button")
                ) {
                    if (state.scanState is ScanState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("در حال دریافت رادار رفت‌وبرگشت و چندطیفی...")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = "شروع پردازش"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_run_scan))
                    }
                }

                if (state.scanState is ScanState.Error) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = "خطا",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.scanState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 2. Live GPS, Static GPS Averaging & Quaternion Status Card
        LiveTelemetryStatusCard(
            state = state,
            onRequestLocationPermission = onRequestLocationPermission,
            onToggleStaticGpsAveraging = onToggleStaticGpsAveraging,
            onApplyAveragedToCenter = onApplyAveragedToCenter,
            onApplyAveragedToGcpTrue = onApplyAveragedToGcpTrue
        )

        // 3. Tactical Radar Heatmap + CFAR + Priority 1 Canvas
        val scanResult = (state.scanState as? ScanState.Success)?.result
        TacticalRadarCanvas(
            summary = scanResult?.matrixSummary,
            selectedCell = state.selectedCell,
            targetPointZero = state.targetPointZero,
            cleanRadarPoints = scanResult?.fusionResult?.cleanRadarPoints.orEmpty(),
            priorityTargets = scanResult?.fusionResult?.priorityTargets.orEmpty(),
            centerLat = scanResult?.centerLat ?: state.latInput.toDoubleOrNull(),
            centerLon = scanResult?.centerLon ?: state.lonInput.toDoubleOrNull(),
            radiusMeters = scanResult?.radiusMeters ?: state.radiusMeters.toDouble(),
            liveGps = state.liveGps,
            headingDeg = state.quaternion.yawDeg,
            onCellSelected = onCellSelected
        )

        // 4. CFAR + Multispectral + Mahalanobis + Savitzky-Golay Fusion Report Card
        if (scanResult != null) {
            FusionReportCard(
                scanResult = scanResult,
                onDetectedTargetSelected = onDetectedTargetSelected
            )
            CinematicTerrain3DCard(
                cells = scanResult.matrixSummary.cells,
                gridSize = scanResult.matrixSummary.gridSize,
                selectedCell = state.selectedCell,
                onCellSelected = onCellSelected
            )
        }

        // 5. Point Zero Guidance & Audio Proximity Alert Card
        val offset = state.liveGuidanceOffset
        val isLocked = offset != null && offset.distanceMeters <= 3.0
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isLocked) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(16.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (state.audioGuidanceEnabled) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = "هشدار صوتی نزدیکی",
                            tint = if (state.audioGuidanceEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "هدایت صوتی و بصری نقطه هدف",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (isLocked) {
                                    "تثبیت روی نقطه صفر (فاصله کمتر از ۳ متر)!"
                                } else {
                                    "بوق هدایت با نزدیک شدن سریع‌تر می‌شود"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isLocked) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Switch(
                        checked = state.audioGuidanceEnabled,
                        onCheckedChange = { onToggleAudio() },
                        modifier = Modifier.testTag("audio_guidance_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = "راهنمای سخنگوی TTS",
                            tint = if (state.ttsGuidanceEnabled) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "اعلام صوتی مسیر و فاصله (TTS)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = if (state.isTtsReady) {
                                    "اعلام خودکار فاصله و جهت گردش هنگام حرکت"
                                } else {
                                    "در حال آماده‌سازی موتور صوتی دستگاه..."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = state.ttsGuidanceEnabled,
                        onCheckedChange = { onToggleTtsGuidance() },
                        modifier = Modifier.testTag("tts_guidance_switch")
                    )
                }

                OutlinedButton(
                    onClick = onSpeakCueNow,
                    enabled = state.targetPointZero != null && state.liveGuidanceOffset != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("speak_cue_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = "اعلام مسیر الان",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("اعلام صوتی مسیر و فاصله الان")
                }

                state.lastSpokenCue?.let { cueText ->
                    Text(
                        text = cueText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                AnimatedVisibility(visible = state.targetPointZero != null) {
                    state.targetPointZero?.let { target ->
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = state.selectedTargetLabel ?: "نقطه هدف قفل‌شده:",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            if (state.gcpEnabled) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "GCP Shift Active: ΔE %+.2fm, ΔN %+.2fm",
                                            state.gcpShiftEastM,
                                            state.gcpShiftNorthM
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Target Point Zero: %.6f, %.6f",
                                        target.latitude,
                                        target.longitude
                                    ),
                                    style = MonoTelemetryStyle
                                )
                            }
                            if (offset != null) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "GPS → Target: %.1f m  |  Azimuth: %.1f°  |  ΔE %+.1fm  ΔN %+.1fm",
                                            offset.distanceMeters,
                                            offset.forwardAzimuthDeg,
                                            offset.deltaEastMeters,
                                            offset.deltaNorthMeters
                                        ),
                                        style = MonoTelemetryStyle,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
