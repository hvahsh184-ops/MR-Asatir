package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.example.R
import com.example.data.RadarAnalysisResult
import com.example.domain.DetectedRadarPoint
import com.example.ui.RadarUiState
import com.example.ui.theme.MonoTelemetryStyle
import java.util.Locale

@Composable
fun LiveTelemetryStatusCard(
    state: RadarUiState,
    onRequestLocationPermission: () -> Unit,
    onToggleStaticGpsAveraging: () -> Unit,
    onApplyAveragedToCenter: () -> Unit,
    onApplyAveragedToGcpTrue: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
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
                        imageVector = Icons.Default.GpsFixed,
                        contentDescription = "وضعیت GPS",
                        tint = if (state.liveGps != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (state.liveGps != null) {
                            "موقعیت زنده GPS متصل"
                        } else if (state.hasLocationPermission) {
                            "در حال قفل کردن ماهواره‌های GPS..."
                        } else {
                            "GPS دستگاه غیرفعال است"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (!state.hasLocationPermission) {
                    FilledTonalButton(
                        onClick = onRequestLocationPermission,
                        modifier = Modifier.testTag("grant_gps_button")
                    ) {
                        Text(stringResource(R.string.btn_grant_location))
                    }
                }
            }

            if (!state.hasLocationPermission) {
                Text(
                    text = stringResource(R.string.permission_rationale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.liveGps != null) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "LAT %.6f  |  LON %.6f  |  ALT %.1fm  |  ±%.1fm",
                            state.liveGps.latitude,
                            state.liveGps.longitude,
                            state.liveGps.altitudeM,
                            state.liveGps.accuracyM
                        ),
                        style = MonoTelemetryStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onToggleStaticGpsAveraging,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("gps_averaging_button")
                    ) {
                        if (state.isAveragingGps) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("توقف میانگین‌گیری (${state.gpsAverageSampleCount}/${state.gpsAverageTargetSamples})")
                        } else {
                            Icon(
                                imageVector = Icons.Default.CenterFocusStrong,
                                contentDescription = "میانگین‌گیری ثابت GPS",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("میانگین‌گیری ثابت GPS")
                        }
                    }
                }

                if (state.averagedGpsLat != null && state.averagedGpsLon != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Static Weighted Avg (%d/%d): %.7f, %.7f (±%.2fm RMS)",
                                    state.gpsAverageSampleCount,
                                    state.gpsAverageTargetSamples,
                                    state.averagedGpsLat,
                                    state.averagedGpsLon,
                                    state.averagedGpsAccuracyM ?: 0f
                                ),
                                style = MonoTelemetryStyle,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onApplyAveragedToCenter,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("apply_avg_gps_center_button")
                            ) {
                                Text("مرکز اسکن")
                            }
                            OutlinedButton(
                                onClick = onApplyAveragedToGcpTrue,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("apply_avg_gps_gcp_button")
                            ) {
                                Text("نقطه واقعی GCP")
                            }
                        }
                    }
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Q=[%.3f, %.3f, %.3f, %.3f]  YAW %.1f°  PITCH %.1f°",
                        state.quaternion.w,
                        state.quaternion.x,
                        state.quaternion.y,
                        state.quaternion.z,
                        state.quaternion.yawDeg,
                        state.quaternion.pitchDeg
                    ),
                    style = MonoTelemetryStyle,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
fun FusionReportCard(
    scanResult: RadarAnalysisResult,
    onDetectedTargetSelected: (DetectedRadarPoint, String) -> Unit
) {
    val fusion = scanResult.fusionResult
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "گزارش ادغام رادار و چندطیفی",
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "گزارش فیلتر رادار CFAR + ماهالانوبیس + ساویتسکی-گولای",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            scanResult.stacWarning?.let { warning ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "هشدار کاتالوگ ماهواره‌ای",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            val align = fusion.alignmentReport
            if (align != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (align.isMinimizedAlert) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (align.isMinimizedAlert) {
                            "هشدار ترازگیری شرق و غرب: اختلاف نسبی به حداقل رسید"
                        } else {
                            "نتیجه هم‌ترازی زیرپیکسلی شرق و غرب (Asc ↔ Desc)"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (align.isMinimizedAlert) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "E-W Disparity: %.2f m → %.2f m  |  NCC: %.2f\nSub-Pixel Shift: ΔE %+.2f px, ΔN %+.2f px\n(Sensor resolution floor: ±10m Sentinel-1 / ±30m DEM)",
                                align.preAlignmentDisparityM,
                                align.postAlignmentDisparityM,
                                align.nccCorrelation,
                                align.shiftEastPixels,
                                align.shiftNorthPixels
                            ),
                            style = MonoTelemetryStyle
                        )
                    }
                }
            }

            val sarDiff = fusion.sarDifference
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val ewDiffStr = sarDiff.meanEwOrbitDiff?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
                val seaDiffStr = sarDiff.meanTwoSeasonSarDiff?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
                val polDiffStr = sarDiff.meanCoCrossPolDiffC?.let { String.format(Locale.US, "%.4f", it) } ?: "-"
                val clDiffStr = sarDiff.meanCrossBandCLDiff?.let { String.format(Locale.US, "%.3f", it) } ?: "-"
                val sgBadge = if (fusion.savitzkyGolayApplied) "5x5 Quadratic ON" else "OFF"
                val peakDmStr = String.format(Locale.US, "%.2f", fusion.peakMahalanobisDm)
                Text(
                    text = "Savitzky-Golay Noise Filter: $sgBadge  |  5D Peak Dm: $peakDmStr\n" +
                        "SAR Diff Processor [${sarDiff.availablePolarizations}]:\n" +
                        "Δ(Asc-Desc): $ewDiffStr | Δ(Sum-Win): $seaDiffStr\n" +
                        "Δ(Co-Cross C): $polDiffStr | Δ(C-L Band): $clDiffStr\n" +
                        "Asc CFAR: ${fusion.ascCfarCount} | Desc CFAR: ${fusion.descCfarCount} | Clean: ${fusion.cleanRadarPoints.size}\n" +
                        "Linear Rejected: ${fusion.linearRejectedCount} | OSM Power Masked: ${fusion.powerInfraMaskedCount}",
                    style = MonoTelemetryStyle,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            fusion.multispectralStats?.let { ms ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    val thermalStr = ms.meanThermalDeltaC?.let {
                        String.format(Locale.US, " | ΔT(Sum-Win): %.1f°C", it)
                    } ?: ""
                    val samStr = ms.minSpectralAngleDeg?.let {
                        String.format(Locale.US, " | Min SAM: %.1f°", it)
                    } ?: ""
                    val feStr = ms.meanIronOxideRatio?.let {
                        String.format(Locale.US, " | FeRatio(B4/B2): %.2f", it)
                    } ?: ""
                    Text(
                        text = String.format(
                            Locale.US,
                            "SCL Clear: %.0f%% | No-Veg: %.0f%% | No-Salt: %.0f%%\n2-Season Carbonate (>3σ): %d (Persisted: %d)%s%s%s",
                            ms.clearSkyPct,
                            ms.noVegetationPct,
                            ms.noSalinityPct,
                            ms.carbonateAnomalyCount,
                            ms.twoSeasonPersistedCount,
                            thermalStr,
                            samStr,
                            feStr
                        ),
                        style = MonoTelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            fusion.materialSignatures?.let { matSig ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    val cStr = matSig.carbonateSaroojSamDeg?.let { String.format(Locale.US, "%.1f°", it) } ?: "-"
                    val fStr = matSig.ironOxideBrickSamDeg?.let { String.format(Locale.US, "%.1f°", it) } ?: "-"
                    val hStr = matSig.hydrothermalGoldCopperHostSamDeg?.let { String.format(Locale.US, "%.1f°", it) } ?: "-"
                    val vStr = matSig.dryVoidMasonrySamDeg?.let { String.format(Locale.US, "%.1f°", it) } ?: "-"
                    Text(
                        text = "6-Band SAM Material Signatures (${matSig.dominantMaterialLabel}):\n" +
                            "Carb/Sarooj: $cStr | Iron/Brick: $fStr | Hydrothermal(Au/Cu Host): $hStr | DryVoid: $vStr",
                        style = MonoTelemetryStyle,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            if (fusion.priorityTargets.isNotEmpty()) {
                Text(
                    text = "۱. اهداف اولویت اول (تلاقی رادار + کربنات/ساروج | لمس = قفل هدایت):",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary
                )
                fusion.priorityTargets.take(5).forEachIndexed { idx, pt ->
                    DetectedTargetRow(
                        title = "[قطعی ${idx + 1}]",
                        point = pt,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        onClick = {
                            onDetectedTargetSelected(
                                pt,
                                "قطعی ${idx + 1} (تلاقی رادار + کربنات/ساروج)"
                            )
                        }
                    )
                }
            } else {
                Text(
                    text = "تلاقی هم‌زمان رادار CFAR و شاخص کربنات/ساروج در این شعاع ۰ نقطه بود.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (fusion.mahalanobisHotspots.isNotEmpty()) {
                Text(
                    text = "۲. ناهنجاری‌های ترکیبی ماهالانوبیس ۵بعدی + ساویتسکی-گولای:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                fusion.mahalanobisHotspots.take(5).forEachIndexed { idx, pt ->
                    DetectedTargetRow(
                        title = "[ماهالانوبیس ${idx + 1}]",
                        point = pt,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            onDetectedTargetSelected(
                                pt,
                                "ناهنجاری ۵بعدی ماهالانوبیس #${idx + 1}"
                            )
                        }
                    )
                }
            }

            if (fusion.cleanRadarPoints.isNotEmpty()) {
                Text(
                    text = "۳. اهداف رادار CFAR پاکسازی‌شده (لمس = قفل هدایت):",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                fusion.cleanRadarPoints.take(5).forEachIndexed { idx, pt ->
                    DetectedTargetRow(
                        title = "[رادار ${idx + 1}]",
                        point = pt,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        onClick = {
                            onDetectedTargetSelected(
                                pt,
                                "هدف رادار CFAR #${idx + 1}"
                            )
                        }
                    )
                }
            }

            val persistentTwoSeason = fusion.twoSeasonSubtraction?.persistentHotspots.orEmpty()
            if (persistentTwoSeason.isNotEmpty()) {
                Text(
                    text = "۴. امضاهای ساختاری پایدار دوفصلی SAR (حذف نویز فصلی):",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                persistentTwoSeason.take(4).forEachIndexed { idx, pt ->
                    DetectedTargetRow(
                        title = "[پایدار ${idx + 1}]",
                        point = pt,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            onDetectedTargetSelected(
                                pt,
                                "امضای پایدار دوفصلی SAR #${idx + 1}"
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DetectedTargetRow(
    title: String,
    point: DetectedRadarPoint,
    accentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = accentColor
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%.6f, %.6f",
                        point.latitude,
                        point.longitude
                    ),
                    style = MonoTelemetryStyle
                )
                val extras = buildList {
                    point.mahalanobisDistance?.let { add(String.format(Locale.US, "Dm:%.2f", it)) }
                    point.ewDisparityM?.let { add(String.format(Locale.US, "ΔEW:%.1fm", it)) }
                    point.thermalDeltaC?.let { add(String.format(Locale.US, "ΔT:%.1f°C", it)) }
                    point.dualPolRatio?.let { add(String.format(Locale.US, "Pol:%.1f", it)) }
                    point.spectralAngleDeg?.let { add(String.format(Locale.US, "SAM:%.1f°", it)) }
                    point.crossBandDiff?.let { add(String.format(Locale.US, "ΔCL:%.2f", it)) }
                }.joinToString(" | ")
                if (extras.isNotEmpty()) {
                    Text(
                        text = extras,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
