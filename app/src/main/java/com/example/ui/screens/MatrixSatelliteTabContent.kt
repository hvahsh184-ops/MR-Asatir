package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.RadarAnalysisResult
import com.example.data.SatellitePassItem
import com.example.ui.RadarUiState
import com.example.ui.ScanState
import com.example.ui.theme.MonoTelemetryStyle
import java.util.Locale

@Composable
fun MatrixSatelliteTabContent(
    state: RadarUiState,
    modifier: Modifier = Modifier
) {
    val result = (state.scanState as? ScanState.Success)?.result

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (result == null) {
            EmptyMatrixPlaceholder()
            return
        }

        // 1. Atmospheric Saastamoinen & Surface Moisture Telemetry
        AtmosphericCard(result)

        // 2. 3D Digital Elevation Model (DEM) Topographic Surface
        CinematicTerrain3DCard(
            cells = result.matrixSummary.cells,
            gridSize = result.matrixSummary.gridSize,
            selectedCell = state.selectedCell,
            onCellSelected = null
        )

        // 3. Two-Season SAR Coregistration & Pixel-Wise Subtraction
        TwoSeasonSarCard(result)

        // 3. Matrix Inversion (4x4 Covariance^-1) & Plane Alignment
        MatrixCard(result)

        // 4. Microsoft Planetary Computer Dual-Band SAR & DEM/LiDAR Catalog
        SatelliteCatalogSection(result)

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun TwoSeasonSarCard(result: RadarAnalysisResult) {
    val twoSeason = result.fusionResult.twoSeasonSubtraction ?: return
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
                    imageVector = Icons.Default.SatelliteAlt,
                    contentDescription = "تفریق دوفصلی SAR",
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "تفریق دوفصلی SAR (هم‌ترازی + حذف نویز فصلی)",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Coreg Shift: ΔE %+.2f px, ΔN %+.2f px  |  NCC: %.2f\n" +
                            "Mean Pixel-Wise |Sum-Win| Diff: %.4f\n" +
                            "Transient Surface Noise Suppressed: %.1f%%\n" +
                            "Persistent Structural Hotspots: %d",
                        twoSeason.coregShiftEastPx,
                        twoSeason.coregShiftNorthPx,
                        twoSeason.coregNccScore,
                        twoSeason.meanAbsDiff,
                        twoSeason.suppressedTransientNoisePct,
                        twoSeason.persistentHotspots.size
                    ),
                    style = MonoTelemetryStyle
                )
            }
        }
    }
}

@Composable
private fun EmptyMatrixPlaceholder() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SatelliteAlt,
                contentDescription = "بدون داده",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(42.dp)
            )
            Text(
                text = "هنوز اسکن زنده انجام نشده است",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "از تب «رادار و هدایت» دکمه پردازش زنده را بزنید تا داده‌های کاتالوگ C/L-Band SAR مایکروسافت، مدل ارتفاعی DEM، تصحیح اتمسفری و ماتریس کوواریانس وارون نمایش داده شود.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AtmosphericCard(result: RadarAnalysisResult) {
    val atm = result.atmospheric
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
                    imageVector = Icons.Default.CloudQueue,
                    contentDescription = "تصحیح اتمسفری",
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "تصحیح اتمسفری تروپوسفری (Saastamoinen زنده)",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = String.format(
                        Locale.US,
                        "Pressure: %.1f hPa  |  Temp: %.1f °C  |  RH: %.0f%%\n" +
                            "Vapor e: %.2f hPa  |  Soil Moisture (0-1cm): %.3f m³/m³\n" +
                            "Refractivity N: %.2f ppm\n" +
                            "ZHD: %.4f m  |  ZWD: %.4f m  |  Slant Delay (38°): %.4f m",
                        atm.pressureHpa,
                        atm.temperatureC,
                        atm.relativeHumidityPct,
                        atm.waterVaporPressureHpa,
                        atm.soilMoistureM3,
                        atm.refractivityN,
                        atm.zenithHydrostaticDelayM,
                        atm.zenithWetDelayM,
                        atm.slantRangeDelayM
                    ),
                    style = MonoTelemetryStyle
                )
            }
        }
    }
}

@Composable
private fun MatrixCard(result: RadarAnalysisResult) {
    val summary = result.matrixSummary
    val fusion = result.fusionResult
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
                    imageVector = Icons.Default.Functions,
                    contentDescription = "محاسبات ماتریسی",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "وارون‌سازی ماتریس Σ⁻¹ (۴×۴ و ۵×۵ چندحسگره) + ساویتسکی-گولای",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = "بردار ۴ بعدی DEM: [باقیمانده ارتفاع، شیب، لاپلاسین، TPI] | بردار ۵ بعدی راستر (۸۱×۸۱): [Asc_SG, Desc_SG, فصلی, کربنات_SG, |ΔDEM|]",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val plane = summary.planeCoefficients
                val matrixText = summary.inverseCovarianceMatrix4x4.joinToString("\n") { row ->
                    row.joinToString("  ") { v -> String.format(Locale.US, "%+8.3f", v) }
                }
                Text(
                    text = String.format(
                        Locale.US,
                        "Savitzky-Golay 5x5: %s  |  5D Raster Peak Dm: %.2f (%d hotspots)\n" +
                            "DEM Plane Fit z = (%.5f)x + (%.5f)y + %.2fm\nMean Elev: %.2f m  |  DEM Peak D²: %.2f\n\nΣ⁻¹ (DEM 4x4 Inverse Covariance):\n%s",
                        if (fusion.savitzkyGolayApplied) "ACTIVE" else "OFF",
                        fusion.peakMahalanobisDm,
                        fusion.mahalanobisHotspots.size,
                        plane.getOrElse(0) { 0.0 },
                        plane.getOrElse(1) { 0.0 },
                        plane.getOrElse(2) { 0.0 },
                        summary.meanElevationM,
                        summary.peakAnomalyCell.mahalanobisDistance,
                        matrixText
                    ),
                    style = MonoTelemetryStyle,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            fusion.materialSignatures?.let { matSig ->
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Material SAM 6-Band [%s]:\nCarb/Sarooj %.1f° | Iron/Clay %.1f° | Quartz-Clay(Au/Cu) %.1f° | DryVoid %.1f°",
                            matSig.dominantMaterialLabel,
                            matSig.carbonateSaroojSamDeg ?: 90.0,
                            matSig.ironOxideBrickSamDeg ?: 90.0,
                            matSig.hydrothermalGoldCopperHostSamDeg ?: 90.0,
                            matSig.dryVoidMasonrySamDeg ?: 90.0
                        ),
                        style = MonoTelemetryStyle,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun SatelliteCatalogSection(result: RadarAnalysisResult) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "کاتالوگ زنده ماهواره‌ای Microsoft Planetary Computer",
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BandSummaryBadge("C-RTC: ${result.cBandCount}", Modifier.weight(1f))
            BandSummaryBadge("S2-L2A: ${result.s2OpticalCount}", Modifier.weight(1f))
            BandSummaryBadge("L-Band: ${result.lBandCount}", Modifier.weight(1f))
            BandSummaryBadge("DEM: ${result.demLidarCount}", Modifier.weight(1f))
        }

        if (result.stacWarning != null) {
            Text(
                text = result.stacWarning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        if (result.satellitePasses.isEmpty()) {
            Text(
                text = "صحنه‌ای در کاتالوگ STAC برای این مختصات یافت نشد.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            result.satellitePasses.forEach { pass ->
                SatellitePassCard(pass)
            }
        }
    }
}

@Composable
private fun BandSummaryBadge(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                RoundedCornerShape(10.dp)
            )
            .padding(vertical = 8.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun SatellitePassCard(item: SatellitePassItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.previewUrl != null) {
                AsyncImage(
                    model = item.previewUrl,
                    contentDescription = item.bandLabel,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.bandLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = "${item.id}\nDate: ${item.datetime} | Pol: ${item.polarizations} | Orbit: ${item.orbitState}",
                        style = MonoTelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
