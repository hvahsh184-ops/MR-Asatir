package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.AudioStftResult
import com.example.domain.MaterialBandReading
import com.example.domain.PassiveDepthTelemetry
import com.example.domain.PassiveMaterialId
import com.example.sensors.AudioPassivePipelineState
import com.example.ui.PassiveAudioViewModel
import com.example.ui.theme.MonoTelemetryStyle
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.util.Locale

@Composable
fun AudioPassiveDetectionTabContent(
    viewModel: PassiveAudioViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingCalibrateAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            if (pendingCalibrateAfterPermission) {
                pendingCalibrateAfterPermission = false
                viewModel.startCalibration()
            } else {
                viewModel.startListening()
            }
        } else {
            pendingCalibrateAfterPermission = false
        }
    }

    val stftModelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(state.frameSeq) {
        val result = state.lastResult ?: return@LaunchedEffect
        if (result.spectrumVicoDb.size >= 2) {
            stftModelProducer.runTransaction {
                lineSeries {
                    series(result.spectrumVicoDb)
                    series(result.noiseFloorVicoDb)
                }
            }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PassiveHeaderControlCard(
            state = state,
            hasAudioPermission = hasAudioPermission,
            onCalibrateClick = {
                if (hasAudioPermission) {
                    viewModel.startCalibration()
                } else {
                    pendingCalibrateAfterPermission = true
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onToggleListening = {
                if (state.isListening) {
                    viewModel.stopListening()
                } else if (hasAudioPermission) {
                    viewModel.startListening()
                } else {
                    pendingCalibrateAfterPermission = false
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onGainChanged = viewModel::setGainMultiplier
        )

        PassiveStftVicoCard(
            state = state,
            modelProducer = stftModelProducer
        )

        TargetDepthTelemetryCard(
            result = state.lastResult,
            isListening = state.isListening
        )

        MaterialToggleListCard(
            enabledMaterials = state.enabledMaterials,
            readings = state.lastResult?.materialReadings,
            onToggleMaterial = viewModel::toggleMaterial,
            onSelectAll = viewModel::selectAllMaterials,
            onSelectNonFerrousOnly = viewModel::selectNonFerrousOnly
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassiveHeaderControlCard(
    state: AudioPassivePipelineState,
    hasAudioPermission: Boolean,
    onCalibrateClick: () -> Unit,
    onToggleListening: () -> Unit,
    onGainChanged: (Float) -> Unit
) {
    val liveDotColor by animateColorAsState(
        targetValue = when {
            state.isCalibrating -> MaterialTheme.colorScheme.tertiary
            state.isListening -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        },
        label = "passive_live_dot"
    )

    val headerGradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.surface
        )
    )

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (state.isListening) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(18.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .background(headerGradient)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "مانیتورینگ پسیو 192kHz",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(liveDotColor)
                        )
                        Text(
                            text = "داشبورد ایزوله پایش پسیو 192kHz",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "پردازشگر Radix-2 STFT مجزا از رادار اصلی (بدون مسدودسازی UI)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TelemetryPill(
                        text = String.format(Locale.US, "%.1f kHz", state.sampleRateHz / 1000f),
                        accent = MaterialTheme.colorScheme.primary
                    )
                    TelemetryPill(
                        text = "Nyquist ${(state.nyquistHz / 1000)} kHz",
                        accent = MaterialTheme.colorScheme.secondary
                    )
                    TelemetryPill(
                        text = "SRC: ${state.audioSourceLabel}",
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.isListening) {
                        TelemetryPill(
                            text = String.format(Locale.US, "DSP %.2f ms", state.dspProcessingMs),
                            accent = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (state.isCalibrated) {
                        TelemetryPill(
                            text = "FLOOR CALIBRATED",
                            accent = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCalibrateClick,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("audio_calibrate_btn")
                ) {
                    Icon(
                        imageVector = if (state.isCalibrated) Icons.Default.CheckCircle else Icons.Default.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            state.isCalibrating -> "کالیبراسیون..."
                            state.isCalibrated -> "کالیبره مجدد"
                            else -> "کالیبره کف نویز"
                        },
                        maxLines = 1
                    )
                }

                Button(
                    onClick = onToggleListening,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isListening) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("audio_passive_toggle_btn")
                ) {
                    Icon(
                        imageVector = if (state.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.isListening) "توقف شنود" else "استارت پسیو",
                        maxLines = 1
                    )
                }
            }

            AnimatedVisibility(visible = state.isCalibrating) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "در حال ثبت الگوی نویز محیط برای تفکیک پایه (${(state.calibrationProgress * 100).toInt()}٪)...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    LinearProgressIndicator(
                        progress = { state.calibrationProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // Gain slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "بهره ورودی آنتن/جک (Pre-FFT Gain)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = String.format(Locale.US, "%.1fx", state.gainMultiplier),
                            style = MonoTelemetryStyle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Slider(
                    value = state.gainMultiplier,
                    onValueChange = onGainChanged,
                    valueRange = 0.1f..10.0f,
                    modifier = Modifier.testTag("audio_gain_slider")
                )
            }

            if (!hasAudioPermission) {
                Text(
                    text = "برای دریافت جریان 192kHz از پورت صوتی/میکروفون، اعطای مجوز RECORD_AUDIO لازم است.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            state.errorMessage?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun PassiveStftVicoCard(
    state: AudioPassivePipelineState,
    modelProducer: CartesianChartModelProducer
) {
    val result = state.lastResult
    val isHighAnomaly = (result?.anomalyScore ?: 0f) >= 72f

    val spectrumColor by animateColorAsState(
        targetValue = if (isHighAnomaly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        label = "stft_spectrum_color"
    )
    val floorColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Waves,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "طیف بلادرنگ STFT (نمودار Vico + خط پایه نویز)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "PEAK: %.0f Hz | SNR: %.1f dB",
                            result?.dominantFrequencyHz ?: 0f,
                            result?.snrDb ?: 0f
                        ),
                        style = MonoTelemetryStyle,
                        color = spectrumColor
                    )
                    Text(
                        text = String.format(Locale.US, "RMS: %.1f dBFS", result?.rmsDbfs ?: -96f),
                        style = MonoTelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (result != null && result.spectrumVicoDb.size >= 2) {
                val lineProvider = LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(fill(spectrumColor)),
                        stroke = LineCartesianLayer.LineStroke.Continuous()
                    ),
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(fill(floorColor)),
                        stroke = LineCartesianLayer.LineStroke.Dashed()
                    )
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    CartesianChartHost(
                        chart = rememberCartesianChart(
                            rememberLineCartesianLayer(lineProvider = lineProvider),
                            startAxis = VerticalAxis.rememberStart(),
                            bottomAxis = HorizontalAxis.rememberBottom()
                        ),
                        modelProducer = modelProducer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(185.dp)
                            .testTag("vico_stft_chart")
                    )
                }

                // Frequency Span Legend (Logarithmic 20 Hz .. Nyquist)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("20 Hz (Void)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("480 Hz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("4.2 kHz", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${state.nyquistHz / 1000} kHz", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB388FF))
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "برای مشاهده طیف لگاریتمی STFT در لحظه، دکمه «استارت پسیو» را لمس کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Compact STFT Waterfall + Live Time-Domain Oscillogram Strip
            if (state.waterfallHistory.isNotEmpty() || (result?.waveformPreview?.isNotEmpty() == true)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                Text(
                    text = "آبشار زمانی-فرکانسی STFT و نوسان‌سنج ورودی",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val wfColorLow = MaterialTheme.colorScheme.primaryContainer
                val wfColorMid = MaterialTheme.colorScheme.secondary
                val wfColorHigh = MaterialTheme.colorScheme.tertiary
                val wfColorPeak = MaterialTheme.colorScheme.error

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF04070B))
                        .testTag("stft_waterfall_canvas")
                ) {
                    val rows = state.waterfallHistory
                    if (rows.isNotEmpty()) {
                        val rowH = size.height / rows.size.toFloat()
                        rows.forEachIndexed { rIdx, rowBins ->
                            val cols = rowBins.size.coerceAtLeast(1)
                            val colW = size.width / cols.toFloat()
                            val y = rIdx * rowH
                            for (cIdx in 0 until cols) {
                                val intensity = rowBins[cIdx].coerceIn(0f, 1f)
                                if (intensity > 0.04f) {
                                    val cellColor = when {
                                        intensity > 0.80f -> wfColorPeak.copy(alpha = 0.95f)
                                        intensity > 0.55f -> wfColorHigh.copy(alpha = 0.85f)
                                        intensity > 0.25f -> wfColorMid.copy(alpha = 0.75f)
                                        else -> wfColorLow.copy(alpha = intensity * 2.5f)
                                    }
                                    drawRect(
                                        color = cellColor,
                                        topLeft = Offset(cIdx * colW, y),
                                        size = Size(colW + 0.5f, rowH + 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }

                val wave = result?.waveformPreview ?: emptyList()
                if (wave.size > 1) {
                    val waveLineColor = MaterialTheme.colorScheme.primary
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF04070B))
                            .padding(horizontal = 4.dp)
                    ) {
                        val midY = size.height / 2f
                        val dx = size.width / (wave.size - 1)
                        val path = Path().apply {
                            moveTo(0f, midY - wave[0] * midY)
                            for (i in 1 until wave.size) {
                                lineTo(i * dx, midY - wave[i] * midY)
                            }
                        }
                        drawPath(path = path, color = waveLineColor, style = Stroke(width = 2.2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetDepthTelemetryCard(
    result: AudioStftResult?,
    isListening: Boolean
) {
    val depth: PassiveDepthTelemetry? = result?.depthTelemetry
    val hasLock = depth?.hasValidLock == true

    val animatedDepthProgress by animateFloatAsState(
        targetValue = if (hasLock && depth != null) (depth.estimatedDepthMeters / 8f).coerceIn(0.04f, 1f) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "depth_gauge"
    )

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (hasLock) 1.5.dp else 1.dp,
                color = if (hasLock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("target_depth_telemetry_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "تله‌متری عمق هدف و پروفیل لایه‌های خاک",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (hasLock && result?.topMatchedMaterial != null) {
                    val matColor = Color(result.topMatchedMaterial.accentColorHex)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(matColor.copy(alpha = 0.16f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = result.topMatchedMaterial.titleFa,
                            style = MaterialTheme.typography.labelSmall,
                            color = matColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Hero Depth Readout + Strata Progress Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "عمق تخمینی بر اساس میرایی بسامدی",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = depth?.depthZoneFa ?: if (isListening) "در حال اسکن..." else "غیرفعال",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasLock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (hasLock && depth != null) {
                                String.format(Locale.US, "%.2f m", depth.estimatedDepthMeters)
                            } else {
                                "--.-- m"
                            },
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasLock) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (hasLock && depth != null) {
                            Text(
                                text = String.format(Locale.US, "±%.2f m", depth.depthErrorMarginMeters),
                                style = MonoTelemetryStyle,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // Depth Stratum Gauge
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedDepthProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.tertiary,
                                        MaterialTheme.colorScheme.secondary,
                                        MaterialTheme.colorScheme.primary
                                    )
                                )
                            )
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0.0m Surface", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        Text("2.5m Mid", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        Text("8.0m+ Deep", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Telemetry Grid (2x2)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryMetricTile(
                    label = "ضریب Q و پهنای باند",
                    value = String.format(Locale.US, "Q=%.1f", depth?.qFactor ?: 1f),
                    accent = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricTile(
                    label = "شاخص اطمینان قفل",
                    value = "${(result?.anomalyScore ?: 0f).toInt()}%",
                    accent = if ((result?.anomalyScore ?: 0f) >= 72f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "هندسه تخمینی: ${depth?.inferredGeometryFa ?: "---"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = result?.analysisVerdict ?: "آماده دریافت سیگنال پسیو 192kHz...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Sub-band energy distribution bars
            if (depth != null) {
                BandEnergyRow("ELF (<50 Hz)", depth.elfEnergyPct, MaterialTheme.colorScheme.primary)
                BandEnergyRow("Void (50–500 Hz)", depth.voidEnergyPct, Color(0xFFFFD54F))
                BandEnergyRow("Telluric (0.5–20 kHz)", depth.telluricEnergyPct, MaterialTheme.colorScheme.secondary)
                BandEnergyRow("High-VLF (20–96 kHz)", depth.highVlfEnergyPct, Color(0xFFB388FF))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaterialToggleListCard(
    enabledMaterials: Set<PassiveMaterialId>,
    readings: List<MaterialBandReading>?,
    onToggleMaterial: (PassiveMaterialId) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNonFerrousOnly: () -> Unit
) {
    val readingMap = remember(readings) {
        readings?.associateBy { it.material } ?: emptyMap()
    }
    val isNonFerrousMode = enabledMaterials.none { it.isFerrousOrClutter } &&
        enabledMaterials.size == PassiveMaterialId.entries.count { !it.isFerrousOrClutter }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .testTag("material_toggle_list_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FilterAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Column {
                    Text(
                        text = "لیست تاگل فیلتر متریال و تفکیک باند 192kHz",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "خاموش کردن هر متریال، باند فرکانسی آن را از محاسبه قفل و عمق هدف فیلتر می‌کند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = enabledMaterials.size == PassiveMaterialId.entries.size,
                    onClick = onSelectAll,
                    label = { Text("همه باندها (۶ متریال)") },
                    modifier = Modifier.testTag("material_preset_all")
                )
                FilterChip(
                    selected = isNonFerrousMode,
                    onClick = onSelectNonFerrousOnly,
                    label = { Text("حذف آهن و تداخل سطحی") },
                    modifier = Modifier.testTag("material_preset_non_ferrous")
                )
            }

            PassiveMaterialId.entries.forEach { material ->
                val isEnabled = material in enabledMaterials
                val bandReading = readingMap[material]
                MaterialToggleRow(
                    material = material,
                    isEnabled = isEnabled,
                    reading = bandReading,
                    onToggle = { onToggleMaterial(material) }
                )
            }
        }
    }
}

@Composable
private fun MaterialToggleRow(
    material: PassiveMaterialId,
    isEnabled: Boolean,
    reading: MaterialBandReading?,
    onToggle: () -> Unit
) {
    val accent = Color(material.accentColorHex)
    val score = reading?.matchScorePct ?: 0
    val animatedScore by animateFloatAsState(
        targetValue = if (isEnabled) (score / 100f).coerceIn(0f, 1f) else 0f,
        label = "mat_score_${material.id}"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isEnabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.background.copy(alpha = 0.7f)
            )
            .border(
                width = 1.dp,
                color = if (isEnabled && score >= 45) accent.copy(alpha = 0.75f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isEnabled) accent else accent.copy(alpha = 0.25f))
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = material.titleFa,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = material.subtitleFa,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = formatBandRange(material.bandMinHz, material.bandMaxHz) +
                                if (reading != null && reading.peakBandHz > 0f) {
                                    String.format(Locale.US, "  |  Peak %.0f Hz (%d%%)", reading.peakBandHz, score)
                                } else {
                                    ""
                                },
                            style = MonoTelemetryStyle,
                            color = if (isEnabled) accent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("material_toggle_${material.id}")
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedScore)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent)
            )
        }
    }
}

@Composable
private fun TelemetryPill(
    text: String,
    accent: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
    }
}

@Composable
private fun TelemetryMetricTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}

@Composable
private fun BandEnergyRow(
    label: String,
    percent: Float,
    color: Color
) {
    val fraction = (percent / 100f).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    String.format(Locale.US, "%.0f%%", percent),
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

private fun formatBandRange(minHz: Float, maxHz: Float): String {
    val minStr = if (minHz >= 1000f) String.format(Locale.US, "%.1fk", minHz / 1000f) else "${minHz.toInt()}"
    val maxStr = if (maxHz >= 1000f) String.format(Locale.US, "%.1fk", maxHz / 1000f) else "${maxHz.toInt()}"
    return "$minStr – $maxStr Hz"
}
