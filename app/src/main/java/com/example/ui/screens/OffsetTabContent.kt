package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ui.OffsetMode
import com.example.ui.RadarUiState
import com.example.ui.theme.MonoTelemetryStyle
import java.util.Locale
import kotlin.math.hypot

@Composable
fun OffsetTabContent(
    state: RadarUiState,
    onModeChanged: (OffsetMode) -> Unit,
    onGroundLatChanged: (String) -> Unit,
    onGroundLonChanged: (String) -> Unit,
    onDeltaEastChanged: (String) -> Unit,
    onDeltaNorthChanged: (String) -> Unit,
    onRecalculate: () -> Unit,
    onGcpEnabledChanged: (Boolean) -> Unit,
    onGcpImageLatChanged: (String) -> Unit,
    onGcpImageLonChanged: (String) -> Unit,
    onGcpTrueLatChanged: (String) -> Unit,
    onGcpTrueLonChanged: (String) -> Unit,
    onFillGcpImageFromTarget: () -> Unit,
    onFillGcpTrueFromGps: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Ground Control Point (GCP) Shift Calibration Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (state.gcpEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(16.dp)
                )
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "کالیبراسیون نقطه کنترل زمینی (GCP Shift)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "حذف خطای جابه‌جایی سیستماتیک صحنه با یک عارضه معلوم (تیر برق، تقاطع جاده یا سازه مشخص)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.gcpEnabled,
                        onCheckedChange = onGcpEnabledChanged,
                        modifier = Modifier.testTag("gcp_enable_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "۱. مختصات روی تصویر/رادار (Image GCP):",
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedButton(
                        onClick = onFillGcpImageFromTarget,
                        enabled = state.rawTargetPointZero != null || state.targetPointZero != null,
                        modifier = Modifier.testTag("fill_gcp_image_button")
                    ) {
                        Text("از هدف رادار")
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = state.gcpImageLatInput,
                            onValueChange = onGcpImageLatChanged,
                            label = { Text("Image Lat") },
                            singleLine = true,
                            textStyle = MonoTelemetryStyle,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("gcp_image_lat_input")
                        )
                        OutlinedTextField(
                            value = state.gcpImageLonInput,
                            onValueChange = onGcpImageLonChanged,
                            label = { Text("Image Lon") },
                            singleLine = true,
                            textStyle = MonoTelemetryStyle,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("gcp_image_lon_input")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "۲. مختصات واقعی روی زمین (True Ground GCP):",
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedButton(
                        onClick = onFillGcpTrueFromGps,
                        enabled = state.averagedGpsLat != null || state.liveGps != null,
                        modifier = Modifier.testTag("fill_gcp_true_gps_button")
                    ) {
                        Text(if (state.averagedGpsLat != null) "از GPS میانگین" else "از GPS زنده")
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = state.gcpTrueLatInput,
                            onValueChange = onGcpTrueLatChanged,
                            label = { Text("True Lat") },
                            singleLine = true,
                            textStyle = MonoTelemetryStyle,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("gcp_true_lat_input")
                        )
                        OutlinedTextField(
                            value = state.gcpTrueLonInput,
                            onValueChange = onGcpTrueLonChanged,
                            label = { Text("True Lon") },
                            singleLine = true,
                            textStyle = MonoTelemetryStyle,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("gcp_true_lon_input")
                        )
                    }

                    val totalShiftM = hypot(state.gcpShiftEastM, state.gcpShiftNorthM)
                    Text(
                        text = String.format(
                            Locale.US,
                            "Systematic Scene Shift: ΔE %+.2f m  |  ΔN %+.2f m  |  Total %.2f m (%s)",
                            state.gcpShiftEastM,
                            state.gcpShiftNorthM,
                            totalShiftM,
                            if (state.gcpEnabled) "APPLIED" else "OFF"
                        ),
                        style = MonoTelemetryStyle,
                        color = if (state.gcpEnabled) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp)
                    )
                }
            }
        }

        // 2. Geodetic Forward / Reverse Calculator Card
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
                Text(
                    text = "محاسبه‌گر دقیق رفت‌وبرگشت نقطه صفر (WGS84 ↔ UTM)",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.offsetMode == OffsetMode.FORWARD_FROM_CENTER,
                        onClick = { onModeChanged(OffsetMode.FORWARD_FROM_CENTER) },
                        label = { Text("نقطه زمین ← اختلاف تا نقطه صفر") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = state.offsetMode == OffsetMode.REVERSE_FROM_GROUND,
                        onClick = { onModeChanged(OffsetMode.REVERSE_FROM_GROUND) },
                        label = { Text("نقطه زمین + آفست ← مختصات جدید") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = "مختصات نقطه مبنای روی زمین:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = state.groundLatInput,
                            onValueChange = onGroundLatChanged,
                            label = { Text("Ground Lat") },
                            singleLine = true,
                            textStyle = MonoTelemetryStyle,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.groundLonInput,
                            onValueChange = onGroundLonChanged,
                            label = { Text("Ground Lon") },
                            singleLine = true,
                            textStyle = MonoTelemetryStyle,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (state.offsetMode == OffsetMode.REVERSE_FROM_GROUND) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = state.deltaEastInput,
                                onValueChange = onDeltaEastChanged,
                                label = { Text("ΔEast (m)") },
                                singleLine = true,
                                textStyle = MonoTelemetryStyle,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = state.deltaNorthInput,
                                onValueChange = onDeltaNorthChanged,
                                label = { Text("ΔNorth (m)") },
                                singleLine = true,
                                textStyle = MonoTelemetryStyle,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Button(
                    onClick = onRecalculate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("recalculate_offset_button")
                ) {
                    Text("محاسبه و هم‌ترازی روی رادار")
                }
            }
        }

        val calc = state.calculatorOffsetResult
        val targetPt = state.computedReverseTarget
        if (calc != null && targetPt != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "خروجی ژئودتیک دقیق (نقطه صفر ↔ نقطه زمین)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Point Zero: %.6f, %.6f\n" +
                                    "Distance: %.2f m\n" +
                                    "Forward Azimuth: %.2f°  |  Back Azimuth: %.2f°\n" +
                                    "ΔEast: %+.2f m  |  ΔNorth: %+.2f m\n" +
                                    "Ground UTM: %d%c  E %.2f  N %.2f\n" +
                                    "Target UTM: %d%c  E %.2f  N %.2f",
                                targetPt.latitude,
                                targetPt.longitude,
                                calc.distanceMeters,
                                calc.forwardAzimuthDeg,
                                calc.backAzimuthDeg,
                                calc.deltaEastMeters,
                                calc.deltaNorthMeters,
                                calc.fromUtm.zone,
                                calc.fromUtm.hemisphere,
                                calc.fromUtm.easting,
                                calc.fromUtm.northing,
                                calc.toUtm.zone,
                                calc.toUtm.hemisphere,
                                calc.toUtm.easting,
                                calc.toUtm.northing
                            ),
                            style = MonoTelemetryStyle
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
