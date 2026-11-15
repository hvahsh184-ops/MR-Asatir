package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.example.domain.DetectedRadarPoint
import com.example.domain.GeoPoint
import com.example.ui.RadarUiState
import com.example.ui.ScanState
import com.example.ui.components.LiveFieldMapCanvas
import com.example.ui.theme.MonoTelemetryStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun LiveMapTabContent(
    state: RadarUiState,
    fetchTile: suspend (String) -> Bitmap?,
    onLatChanged: (String) -> Unit,
    onLonChanged: (String) -> Unit,
    onRadiusChanged: (Float) -> Unit,
    onRunScan: () -> Unit,
    onDetectedTargetSelected: (DetectedRadarPoint, String) -> Unit,
    onCustomTargetSelected: (GeoPoint) -> Unit,
    onToggleAudio: () -> Unit,
    onToggleTtsGuidance: () -> Unit,
    onSpeakCueNow: () -> Unit,
    onCaptureTargetImagery: () -> Unit,
    onSendImageToAi: (Bitmap, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scanResult = (state.scanState as? ScanState.Success)?.result
    val offset = state.liveGuidanceOffset
    val isLocked = offset != null && offset.distanceMeters <= 3.0
    var showMapScanInputs by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Turn-by-Turn Field Navigation HUD
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isLocked) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "ناوبری زنده",
                            tint = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = state.selectedTargetLabel ?: "مسیر زنده تا نقطه هدف",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = when {
                                    state.liveGps == null -> "در انتظار قفل GPS گوشی روی موقعیت شما..."
                                    state.targetPointZero == null -> "ابتدا یک هدف انتخاب یا روی نقشه لمس طولانی کنید"
                                    isLocked -> "به نقطه هدف رسیدید (فاصله کمتر از ۳ متر)!"
                                    else -> "جهت حرکت را مطابق خط سبز روی نقشه دنبال کنید"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (state.audioGuidanceEnabled) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = "بوق هدایت",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = state.audioGuidanceEnabled,
                            onCheckedChange = { onToggleAudio() },
                            modifier = Modifier.testTag("map_audio_switch")
                        )
                    }
                }

                if (offset != null && state.targetPointZero != null) {
                    val relTurnDeg = ((offset.forwardAzimuthDeg - state.quaternion.yawDeg + 540.0) % 360.0) - 180.0
                    val turnHint = when {
                        abs(relTurnDeg) <= 15.0 -> "مستقیم جلو"
                        relTurnDeg > 15.0 -> String.format(Locale.US, "%.0f° به راست بچرخید", relTurnDeg)
                        else -> String.format(Locale.US, "%.0f° به چپ بچرخید", -relTurnDeg)
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Dist: %.1f m  |  Bearing: %.0f°  |  %s\nΔE: %+.1f m  |  ΔN: %+.1f m  |  Target: %.6f, %.6f",
                                offset.distanceMeters,
                                offset.forwardAzimuthDeg,
                                turnHint,
                                offset.deltaEastMeters,
                                offset.deltaNorthMeters,
                                state.targetPointZero.latitude,
                                state.targetPointZero.longitude
                            ),
                            style = MonoTelemetryStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.ttsGuidanceEnabled,
                        onClick = onToggleTtsGuidance,
                        label = { Text("اعلام صوتی TTS") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = "فعال‌سازی اعلام صوتی TTS",
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("map_tts_switch")
                    )
                    OutlinedButton(
                        onClick = onSpeakCueNow,
                        enabled = offset != null && state.targetPointZero != null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("map_speak_cue_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = "اعلام مسیر الان",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("اعلام مسیر الان")
                    }
                }

                state.lastSpokenCue?.let { cueText ->
                    Text(
                        text = cueText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Quick Coordinates & Radius Strip right inside Map Tab
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "مختصات و شعاع اسکن: ${state.radiusMeters.roundToInt()} متر",
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedButton(
                        onClick = { showMapScanInputs = !showMapScanInputs },
                        modifier = Modifier.testTag("map_edit_coords_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.EditLocationAlt,
                            contentDescription = "ویرایش مختصات و شعاع",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (showMapScanInputs) "بستن" else "ورود مختصات و شعاع")
                    }
                }

                AnimatedVisibility(visible = showMapScanInputs) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.latInput,
                                    onValueChange = onLatChanged,
                                    label = { Text("Latitude") },
                                    singleLine = true,
                                    textStyle = MonoTelemetryStyle,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = state.lonInput,
                                    onValueChange = onLonChanged,
                                    label = { Text("Longitude") },
                                    singleLine = true,
                                    textStyle = MonoTelemetryStyle,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Slider(
                            value = state.radiusMeters,
                            onValueChange = onRadiusChanged,
                            valueRange = 100f..5000f,
                            steps = 48
                        )
                        Button(
                            onClick = onRunScan,
                            enabled = state.scanState !is ScanState.Loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Radar, contentDescription = "اجرای اسکن")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (state.scanState is ScanState.Loading) "در حال اسکن..." else "اسکن رادار و چندطیفی روی نقشه")
                        }
                    }
                }
            }
        }

        // 2. Real Interactive Satellite / OSM Navigation Map
        LiveFieldMapCanvas(
            liveGps = state.liveGps,
            headingDeg = state.quaternion.yawDeg,
            targetPointZero = state.targetPointZero,
            scanCenterLat = scanResult?.centerLat ?: state.latInput.toDoubleOrNull(),
            scanCenterLon = scanResult?.centerLon ?: state.lonInput.toDoubleOrNull(),
            scanRadiusMeters = scanResult?.radiusMeters ?: state.radiusMeters.toDouble(),
            cleanRadarPoints = scanResult?.fusionResult?.cleanRadarPoints.orEmpty(),
            mahalanobisHotspots = scanResult?.fusionResult?.mahalanobisHotspots.orEmpty(),
            priorityTargets = scanResult?.fusionResult?.priorityTargets.orEmpty(),
            fetchTile = fetchTile,
            onDetectedTargetSelected = onDetectedTargetSelected,
            onMapLongOrCustomSelect = onCustomTargetSelected
        )

        // 3. Target Satellite Imagery Capture & Visual Inspection Card
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SatelliteAlt,
                        contentDescription = "تصاویر ماهواره‌ای نقطه هدف",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "دریافت تصویر ماهواره‌ای نقطه هدف (±۱۸۰ متر)",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Button(
                    onClick = onCaptureTargetImagery,
                    enabled = !state.isLoadingTargetImagery &&
                        (state.targetPointZero != null || state.latInput.toDoubleOrNull() != null),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("capture_target_imagery_button")
                ) {
                    if (state.isLoadingTargetImagery) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("در حال دریافت تصاویر نوری، SWIR و SAR از نقطه هدف...")
                    } else {
                        Icon(Icons.Default.CameraAlt, contentDescription = "تصویربرداری از نقطه")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("تصویربرداری ماهواره‌ای از همین مختصات")
                    }
                }

                state.targetImageryError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                state.targetImagery?.let { bundle ->
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Center: %.6f, %.6f  |  S2: %s  |  SAR: %s",
                                bundle.latitude,
                                bundle.longitude,
                                bundle.opticalDate ?: "-",
                                bundle.sarDate ?: "-"
                            ),
                            style = MonoTelemetryStyle,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TargetBandThumbnail(
                            title = "نوری RGB",
                            bitmap = bundle.trueColorRgb,
                            onSendToAi = { bmp ->
                                onSendImageToAi(
                                    bmp,
                                    "تصویر ماهواره‌ای نوری (Sentinel-2 RGB) مختصات ${bundle.latitude}, ${bundle.longitude} را از نظر آثار سطحی، خطواره‌ها و بافت خاک بررسی کن."
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TargetBandThumbnail(
                            title = "کانی/ساروج SWIR",
                            bitmap = bundle.mineralSwir,
                            onSendToAi = { bmp ->
                                onSendImageToAi(
                                    bmp,
                                    "تصویر باندهای حرارتی/کانی‌شناسی (SWIR B12-B11-B04) مختصات ${bundle.latitude}, ${bundle.longitude} را از نظر ناهنجاری کربنات و آهک بررسی کن."
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TargetBandThumbnail(
                            title = "رادار SAR VV",
                            bitmap = bundle.sarBackscatter,
                            onSendToAi = { bmp ->
                                onSendImageToAi(
                                    bmp,
                                    "تصویر بازتاب رادار Sentinel-1 SAR مختصات ${bundle.latitude}, ${bundle.longitude} را از نظر بازتاب‌های نقطه‌ای یا دیواره‌های هندسی بررسی کن."
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun TargetBandThumbnail(
    title: String,
    bitmap: Bitmap?,
    onSendToAi: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (bitmap != null) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                )
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .border(1.5.dp, Color(0xFFFF3B5C), RoundedCornerShape(7.dp))
                )
            }
            OutlinedButton(
                onClick = { onSendToAi(bitmap) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "ارسال به دستیار",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("تحلیل", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(0xFF0E1622), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("ناموجود", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
