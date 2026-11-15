package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.domain.FieldAnomalyPin
import com.example.domain.FieldReceiverMath
import com.example.domain.GeoPoint
import com.example.domain.OpticalGridStats
import com.example.domain.ThermalStylePixelResult
import com.example.ui.RadarUiState
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
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldReceiverTabContent(
    state: RadarUiState,
    onCalibrateBaseline: () -> Unit,
    onSensitivityChanged: (Float) -> Unit,
    onSweepSpeedChanged: (Float) -> Unit,
    onDepthFilterModeChanged: (Int) -> Unit,
    onToggleMagAudio: () -> Unit,
    onToggleAutoDetectionSound: () -> Unit,
    onOpticalFrameAnalyzed: (ThermalStylePixelResult?, Boolean) -> Unit,
    onLiveFrameCaptured: (Bitmap?) -> Unit = {},
    onManualTrimRam: () -> Unit,
    onCycleOverlayMode: () -> Unit,
    onSelectCaptureAngle: (Int) -> Unit,
    onRecordPin: (Bitmap?, OpticalGridStats?) -> Unit,
    onClearPins: () -> Unit,
    onLockPinOnMap: (GeoPoint) -> Unit,
    onSendPinToAi: (Bitmap, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isCameraActive by remember { mutableStateOf(false) }
    var isTorchOn by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var liveOpticalStats by remember { mutableStateOf<OpticalGridStats?>(null) }
    var thermalFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var thermalFrameMeta by remember { mutableStateOf<ThermalStylePixelResult?>(null) }
    var filterOpacity by remember { mutableFloatStateOf(0.92f) }
    var thermalGain by remember { mutableFloatStateOf(1.6f) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val latestOverlayMode = rememberUpdatedState(state.receiverOpticalOverlayMode)
    val latestThermalGain = rememberUpdatedState(thermalGain)

    val modelProducer = remember { CartesianChartModelProducer() }
    val history = state.magHistoryUt
    val baseUt = state.magBaselineMeanUt ?: history.lastOrNull() ?: 45.0

    LaunchedEffect(history, baseUt) {
        if (history.size >= 2) {
            modelProducer.runTransaction {
                lineSeries {
                    series(history)
                    series(List(history.size) { baseUt })
                }
            }
        }
    }

    LaunchedEffect(thermalFrameMeta, isCameraActive) {
        onOpticalFrameAnalyzed(if (isCameraActive) thermalFrameMeta else null, isCameraActive)
    }

    LaunchedEffect(isCameraActive, state.isAiCompanionSheetOpen, state.aiWatchdogEnabled) {
        if (!isCameraActive) {
            onLiveFrameCaptured(null)
        } else {
            // Delay briefly for PreviewView surface readiness, then sync live frame periodically
            kotlinx.coroutines.delay(900L)
            while (isCameraActive) {
                val rawPreviewBmp = runCatching { previewViewRef?.bitmap }.getOrNull()
                val snapshot = rawPreviewBmp ?: thermalFrameBitmap
                if (snapshot != null) {
                    onLiveFrameCaptured(snapshot)
                }
                val intervalMs = if (state.isAiCompanionSheetOpen || state.aiWatchdogEnabled) 1600L else 4000L
                kotlinx.coroutines.delay(intervalMs)
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isCameraActive = true
            cameraError = null
        } else {
            cameraError = "برای مشاهده دوربین زنده در گیرنده میدانی، مجوز دوربین لازم است."
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onOpticalFrameAnalyzed(null, false)
            onLiveFrameCaptured(null)
            runCatching {
                cameraRef?.cameraControl?.enableTorch(false)
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
            analyzerExecutor.shutdown()
        }
    }

    val absDelta = abs(state.magDeltaUt)
    val isAnomalyAlert = state.isMagConfirmedAlert || state.confirmedDetectionLevel >= 2
    val isPhoneLevel = FieldReceiverMath.isPhoneLevelForGroundSweep(
        pitchDeg = state.quaternion.pitchDeg,
        rollDeg = state.quaternion.rollDeg
    )
    val overlayLabel = when (state.receiverOpticalOverlayMode) {
        1 -> "فیلتر حرارتی‌نما (Ironbow اثر سطحی)"
        2 -> "فیلتر تفکیک رطوبت و آهک خاک"
        3 -> "فیلتر هاله اکسید و شکستگی"
        4 -> "حالت تخصصی تشخیص ساروج سطحی"
        else -> "دوربین خام بدون فیلتر"
    }

    val angleLabels = listOf("مرکز (بالا)", "شمال", "شرق", "جنوب", "غرب")

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0. Unmistakable Real Detection Alert + Anti-Fake Indoor Guard + 90% Auto RAM Guard
        val detLevel = state.confirmedDetectionLevel
        val bannerBg = when (detLevel) {
            3 -> Color(0xFF420A16)
            2 -> Color(0xFF3E1C07)
            1 -> Color(0xFF0A2F3D)
            else -> if (state.indoorInterferenceWarning != null) Color(0xFF2D2106) else MaterialTheme.colorScheme.surface
        }
        val bannerBorder = when (detLevel) {
            3 -> Color(0xFFFF3B5C)
            2 -> Color(0xFFFF9100)
            1 -> Color(0xFF00E5FF)
            else -> if (state.indoorInterferenceWarning != null) Color(0xFFFFC107) else MaterialTheme.colorScheme.outline
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = bannerBg),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(if (detLevel > 0) 2.dp else 1.dp, bannerBorder, RoundedCornerShape(16.dp))
                .testTag("confirmed_detection_alert_card")
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
                            imageVector = when {
                                detLevel > 0 -> Icons.Default.WarningAmber
                                else -> Icons.Default.Shield
                            },
                            contentDescription = "وضعیت هشدار واقعی",
                            tint = bannerBorder
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.confirmedAlertTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (detLevel > 0) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = onToggleAutoDetectionSound,
                        modifier = Modifier.testTag("toggle_auto_detection_sound_button")
                    ) {
                        Icon(
                            imageVector = if (state.autoDetectionSoundEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                            contentDescription = "آژیر و لرزش خودکار کشف",
                            tint = if (state.autoDetectionSoundEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = state.confirmedAlertDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detLevel > 0) Color(0xFFEAF2F8) else MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Live 90% Auto-RAM Guard Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = "حافظه رم",
                            tint = if (state.ramUsagePercent >= 80) Color(0xFFFFAB00) else Color(0xFF69F0AE),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "رم برنامه: ${state.ramUsagePercent}٪ (${state.ramUsedMb}/${state.ramMaxMb} MB) • تخلیه خودکار در ۹۰٪",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.ramUsagePercent >= 80) Color(0xFFFFD54F) else Color(0xFFB9F6CA)
                            )
                            state.lastAutoRamCleanStatus?.let { cleanMsg ->
                                Text(
                                    text = cleanMsg,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            if (!isCameraActive) thermalFrameBitmap = null
                            onManualTrimRam()
                        },
                        modifier = Modifier.testTag("manual_trim_ram_button")
                    ) {
                        Text("تخلیه رم", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 1. Live Magnetometer + Sweep Stability Header
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isAnomalyAlert) {
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
                    if (isAnomalyAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
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
                            imageVector = Icons.Default.Sensors,
                            contentDescription = "گیرنده مگنتومتر و دوربین",
                            tint = if (isAnomalyAlert) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "گیرنده سطحی مگنتومتر + دوربین زنده",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = when {
                                    !state.hasMagnetometer -> "سنسور مگنتومتر سخت‌افزاری روی این گوشی موجود نیست."
                                    state.isCalibratingMagBaseline -> "در حال قفل صفر محیط روی خاک (${state.magCalibrationCount}/25)..."
                                    isAnomalyAlert -> "هشدار تاییدشده مگنتومتر (ΔB = ${String.format(Locale.US, "%+.1f", state.magDeltaUt)} µT)!"
                                    !isPhoneLevel -> "گوشی را صاف و ثابت (۱۰–۱۵ سانت بالای خاک) نگه دارید."
                                    else -> "وضعیت تراز و کالیبراسیون واقعی مناسب است"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    isAnomalyAlert -> MaterialTheme.colorScheme.error
                                    !isPhoneLevel || state.isCalibratingMagBaseline -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (state.magAudioEnabled) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = "بوق مغناطیسی گیگر",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = state.magAudioEnabled,
                            onCheckedChange = { onToggleMagAudio() },
                            modifier = Modifier.testTag("mag_audio_switch")
                        )
                    }
                }

                Text(
                    text = "جاروب سطحی ۱۰–۵۰ سانت (µT + STFT) | فیلترهای نوری RGB کنتراست خاک و ساروج",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    val mag = state.magnetometerReading
                    Text(
                        text = if (mag != null) {
                            String.format(
                                Locale.US,
                                "|B|: %.2f µT | Base: %.2f µT | ΔB: %+.2f µT | Floor: %.1f µT\nBx: %+.1f  By: %+.1f  Bz: %+.1f µT  |  Tilt P:%.0f° R:%.0f°",
                                mag.magnitudeUt,
                                baseUt,
                                state.magDeltaUt,
                                state.magEffectiveFloorUt,
                                mag.bxUt,
                                mag.byUt,
                                mag.bzUt,
                                state.quaternion.pitchDeg,
                                state.quaternion.rollDeg
                            ) + String.format(
                                Locale.US,
                                "\nMotion: %.2f m/s² (%s) | Light: %s | Baro: %s",
                                state.linearAccelMps2,
                                if (state.linearAccelMps2 > 2.3) "SHAKE!" else if (state.linearAccelMps2 < 0.08) "Static" else "Sweep",
                                state.ambientLightLux?.let { "${it.roundToInt()} lx" } ?: "—",
                                state.baroPressureHpa?.let { "${String.format(Locale.US, "%.1f", it)} hPa" } ?: "—"
                            )
                        } else {
                            "Waiting for TYPE_MAGNETIC_FIELD sensor samples..."
                        },
                        style = MonoTelemetryStyle,
                        color = if (isAnomalyAlert) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
        }

        // 2. Live CameraX HUD + 4-Mode Optical Contrast Overlay
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (isCameraActive) {
                                isCameraActive = false
                                isTorchOn = false
                                runCatching {
                                    cameraRef?.cameraControl?.enableTorch(false)
                                    ProcessCameraProvider.getInstance(context).get().unbindAll()
                                }
                                cameraRef = null
                                thermalFrameBitmap = null
                                thermalFrameMeta = null
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    isCameraActive = true
                                    cameraError = null
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("receiver_camera_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isCameraActive) Icons.Default.VideocamOff else Icons.Default.Videocam,
                            contentDescription = "فعال‌سازی دوربین گیرنده",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isCameraActive) "توقف دوربین" else "روشن کردن دوربین")
                    }

                    OutlinedButton(
                        onClick = onCycleOverlayMode,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("receiver_overlay_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "تغییر فیلتر نوری",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            when (state.receiverOpticalOverlayMode) {
                                1 -> "حرارتی‌نما Ironbow"
                                2 -> "رطوبت/آهک خاک"
                                3 -> "اکسید و شکستگی"
                                4 -> "تشخیص ساروج سطحی"
                                else -> "دوربین خام"
                            }
                        )
                    }
                }

                cameraError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A1018))
                        .border(
                            width = if (isAnomalyAlert) 2.dp else 1.dp,
                            color = if (isAnomalyAlert) Color(0xFFFF3B5C) else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .pointerInput(isCameraActive) {
                            if (isCameraActive) {
                                detectTapGestures { offset ->
                                    val pv = previewViewRef ?: return@detectTapGestures
                                    val cam = cameraRef ?: return@detectTapGestures
                                    val pt = pv.meteringPointFactory.createPoint(offset.x, offset.y)
                                    val action = FocusMeteringAction.Builder(pt).build()
                                    runCatching { cam.cameraControl.startFocusAndMetering(action) }
                                }
                            }
                        }
                ) {
                    if (isCameraActive) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                previewViewRef = previewView
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    runCatching {
                                        val provider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.surfaceProvider = previewView.surfaceProvider
                                        }
                                        val analysis = ImageAnalysis.Builder()
                                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                            .build()
                                            .also { imageAnalysis ->
                                                imageAnalysis.setAnalyzer(analyzerExecutor) { proxy ->
                                                    try {
                                                        val planes = proxy.planes
                                                        if (planes.size >= 3) {
                                                            val yBuf = planes[0].buffer
                                                            val uBuf = planes[1].buffer
                                                            val vBuf = planes[2].buffer
                                                            val yArr = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
                                                            val uArr = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
                                                            val vArr = ByteArray(vBuf.remaining()).also { vBuf.get(it) }
                                                            val stats = FieldReceiverMath.analyzeYuvFrameGrid(
                                                                yBytes = yArr,
                                                                uBytes = uArr,
                                                                vBytes = vArr,
                                                                width = proxy.width,
                                                                height = proxy.height,
                                                                yRowStride = planes[0].rowStride,
                                                                uvRowStride = planes[1].rowStride,
                                                                uvPixelStride = planes[1].pixelStride,
                                                                gridCols = 16,
                                                                gridRows = 12
                                                            )
                                                            liveOpticalStats = stats
                                                            val activeMode = latestOverlayMode.value
                                                            if (activeMode != 0) {
                                                                val (bmp, meta) = FieldReceiverMath.renderThermalStyleBitmap(
                                                                    yBytes = yArr,
                                                                    uBytes = uArr,
                                                                    vBytes = vArr,
                                                                    srcWidth = proxy.width,
                                                                    srcHeight = proxy.height,
                                                                    yRowStride = planes[0].rowStride,
                                                                    uvRowStride = planes[1].rowStride,
                                                                    uvPixelStride = planes[1].pixelStride,
                                                                    rotationDegrees = proxy.imageInfo.rotationDegrees,
                                                                    mode = activeMode,
                                                                    gain = latestThermalGain.value
                                                                )
                                                                thermalFrameBitmap = bmp
                                                                thermalFrameMeta = meta
                                                            } else {
                                                                thermalFrameBitmap = null
                                                                thermalFrameMeta = null
                                                            }
                                                        }
                                                    } finally {
                                                        proxy.close()
                                                    }
                                                }
                                            }
                                        provider.unbindAll()
                                        cameraRef = provider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                        )
                                    }.onFailure { err ->
                                        cameraError = "خطای دوربین: ${err.localizedMessage}"
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterCenterFocus,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "برای هم‌زمان‌سازی تصویر حرارتی‌نمای اثر سطحی خاک و مگنتومتر، «روشن کردن دوربین» را بزنید.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val activeFilterBmp = thermalFrameBitmap
                    val activeFilterMeta = thermalFrameMeta
                    val overlayMode = state.receiverOpticalOverlayMode
                    if (isCameraActive && overlayMode != 0 && activeFilterBmp != null) {
                        Image(
                            bitmap = activeFilterBmp.asImageBitmap(),
                            contentDescription = "فیلتر بصری حرارتی‌نمای اثر سطحی خاک",
                            contentScale = ContentScale.Crop,
                            filterQuality = FilterQuality.Medium,
                            alpha = filterOpacity,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("thermal_style_camera_overlay")
                        )

                        // Thermal-style palette legend bar on left edge
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 8.dp)
                                .width(10.dp)
                                .height(120.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = when (overlayMode) {
                                            2 -> listOf(Color(0xFFFF2E54), Color(0xFFFFA800), Color(0xFF1A1C24), Color(0xFF00C3FF), Color(0xFF00308C))
                                            3 -> listOf(Color(0xFFFFC440), Color(0xFF69F0AE), Color(0xFF2B2D38))
                                            4 -> listOf(Color(0xFF5FEBE0), Color(0xFF28B8A2), Color(0xFF1F2329))
                                            else -> listOf(Color(0xFFFFFCE8), Color(0xFFFAB822), Color(0xFFDC4829), Color(0xFF8A1C6C), Color(0xFF2A0B5C), Color(0xFF050312))
                                        }
                                    )
                                )
                                .border(0.8.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                        )
                    }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val ringColor = if (isAnomalyAlert) Color(0xFFFF3B5C) else Color(0xFF00E5FF)
                        val radius = (min(size.width, size.height) * 0.17f) +
                            (absDelta.coerceAtMost(40.0).toFloat() * 0.6f)
                        drawCircle(
                            color = ringColor.copy(alpha = 0.85f),
                            radius = radius,
                            center = Offset(cx, cy),
                            style = Stroke(width = if (isAnomalyAlert) 4f else 2.2f)
                        )
                        drawLine(ringColor, Offset(cx - 26f, cy), Offset(cx + 26f, cy), strokeWidth = 2f)
                        drawLine(ringColor, Offset(cx, cy - 26f), Offset(cx, cy + 26f), strokeWidth = 2f)

                        // Lock real bounding box directly onto confirmed localized hotspot cluster
                        if (isCameraActive && state.isOpticalConfirmedAlert && activeFilterMeta != null && activeFilterMeta.isLocalizedOpticalAnomaly) {
                            val left = activeFilterMeta.hotspotBoxMinX * size.width
                            val top = activeFilterMeta.hotspotBoxMinY * size.height
                            val boxW = ((activeFilterMeta.hotspotBoxMaxX - activeFilterMeta.hotspotBoxMinX) * size.width).coerceAtLeast(24f)
                            val boxH = ((activeFilterMeta.hotspotBoxMaxY - activeFilterMeta.hotspotBoxMinY) * size.height).coerceAtLeast(24f)
                            val targetColor = if (state.confirmedDetectionLevel == 3) Color(0xFFFF3B5C) else Color(0xFFFFD600)
                            drawRect(
                                color = targetColor,
                                topLeft = Offset(left, top),
                                size = Size(boxW, boxH),
                                style = Stroke(width = 4f)
                            )
                            val hx = activeFilterMeta.hotspotCentroidX * size.width
                            val hy = activeFilterMeta.hotspotCentroidY * size.height
                            drawCircle(color = targetColor, radius = 8f, center = Offset(hx, hy))
                        }
                    }

                    if (isCameraActive) {
                        IconButton(
                            onClick = {
                                val next = !isTorchOn
                                isTorchOn = next
                                runCatching { cameraRef?.cameraControl?.enableTorch(next) }
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color(0x99080E16), RoundedCornerShape(10.dp))
                                .testTag("receiver_torch_button")
                        ) {
                            Icon(
                                imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "چراغ قوه دوربین",
                                tint = if (isTorchOn) Color(0xFFFFEA00) else Color.White
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0xAA080E16))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = overlayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "ΔB %+.1f µT%s",
                                    state.magDeltaUt,
                                    activeFilterMeta?.let {
                                        String.format(Locale.US, " | Zs %.1f | %.0f%%", it.peakSurfaceContrastZ, it.hotspotCoveragePct)
                                    } ?: liveOpticalStats?.let {
                                        String.format(Locale.US, " | Edge %.2f", it.edgeDensity)
                                    } ?: ""
                                ),
                                style = MonoTelemetryStyle,
                                color = if (isAnomalyAlert) Color(0xFFFF5C7A) else Color(0xFF00E5FF)
                            )
                        }
                    }
                }

                if (state.receiverOpticalOverlayMode != 0) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "شدت فیلتر (${(filterOpacity * 100).roundToInt()}٪)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.width(108.dp)
                            )
                            Slider(
                                value = filterOpacity,
                                onValueChange = { filterOpacity = it },
                                valueRange = 0.35f..1.0f,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("thermal_filter_opacity_slider")
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "حساسیت آنومالی ${String.format(Locale.US, "%.1fx", thermalGain)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.width(108.dp)
                            )
                            Slider(
                                value = thermalGain,
                                onValueChange = { thermalGain = it },
                                valueRange = 0.8f..3.2f,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("thermal_gain_slider")
                            )
                        }
                        if (state.receiverOpticalOverlayMode == 4) {
                            Text(
                                text = "راهنمای تشخیص سریع ساروج روی سطح: لکه فیروزه‌ای روشن با مرز شکسته و زاویه‌دار + سطح غیرجاذب رطوبت (قطره آب رویش سر می‌خورد و با خاک رس نرم فرق دارد).",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF64FFDA)
                            )
                        }
                    }
                }
            }
        }

        // 3. Real-Time Vico Line Chart + Multi-Angle Capture & Calibration Controls
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
                        text = "نمودار خطی زنده مگنتومتر (Vico |B| و خط پایه)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "آستانه: ${state.magSensitivityThresholdUt.roundToInt()} µT",
                        style = MonoTelemetryStyle,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                if (history.size >= 2) {
                    val liveColor = if (isAnomalyAlert) Color(0xFFFF3B5C) else MaterialTheme.colorScheme.primary
                    val baseColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                    val lineProvider = LineCartesianLayer.LineProvider.series(
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(liveColor)),
                            stroke = LineCartesianLayer.LineStroke.Continuous()
                        ),
                        LineCartesianLayer.rememberLine(
                            fill = LineCartesianLayer.LineFill.single(fill(baseColor)),
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
                                .height(165.dp)
                                .testTag("vico_magnetometer_chart")
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF09111A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (state.hasMagnetometer) {
                                "در حال دریافت جریان داده مگنتومتر برای رسم نمودار زنده..."
                            } else {
                                "سنسور مگنتومتر سخت‌افزاری روی دستگاه یافت نشد."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Slider(
                    value = state.magSensitivityThresholdUt,
                    onValueChange = onSensitivityChanged,
                    valueRange = 3f..50f,
                    steps = 46,
                    modifier = Modifier.testTag("mag_sensitivity_slider")
                )

                Text(
                    text = "زاویه ثبت عکس دور تا دور نقطه هدف (مختصات‌دار):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    angleLabels.forEachIndexed { idx, label ->
                        FilterChip(
                            selected = state.receiverCaptureAngleIndex == idx,
                            onClick = { onSelectCaptureAngle(idx) },
                            label = { Text(label) },
                            modifier = Modifier.testTag("capture_angle_chip_$idx")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCalibrateBaseline,
                        enabled = state.hasMagnetometer && !state.isCalibratingMagBaseline,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("calibrate_mag_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CenterFocusStrong,
                            contentDescription = "کالیبره خط پایه محیط",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (state.isCalibratingMagBaseline) {
                                "صفر کردن (${state.magCalibrationCount}/25)"
                            } else {
                                "صفر محیط (Baseline)"
                            }
                        )
                    }

                    Button(
                        onClick = {
                            val bmp = if (state.receiverOpticalOverlayMode != 0 && thermalFrameBitmap != null) {
                                thermalFrameBitmap
                            } else {
                                previewViewRef?.bitmap
                            }
                            onRecordPin(bmp, liveOpticalStats)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pin_receiver_spot_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "ثبت نقطه با تله‌متری",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ثبت زاویه و فریم")
                    }
                }
            }
        }

        // 3B. Live STFT Frequency Spectrum + Waterfall + Apparent Dipole Depth Envelope
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
                    Text(
                        text = "تحلیل طیفی زنده STFT مگنتومتر (فرکانس/طول‌موج)",
                        style = MaterialTheme.typography.titleSmall
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = String.format(Locale.US, "%.0f Hz | v=%.2f m/s", state.magSampleRateHz, state.magSweepSpeedMps),
                            style = MonoTelemetryStyle,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Text(
                    text = "تبدیل STFT نوسان ΔB حین حرکت یکنواخت را به باندهای فرکانسی می‌شکند. جسم نزدیکِ سطح، پالس تیز و فرکانس‌بالا و توده پهن‌تر موج کم‌فرکانس می‌سازد؛ عمق نمایشی فقط تخمین تئوری دیپول (حداکثر ~۱.۵ متر) است.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        Pair(0.25f, "جاروب آهسته (0.25m/s)"),
                        Pair(0.40f, "جاروب معمولی (0.40m/s)"),
                        Pair(0.70f, "جاروب سریع (0.70m/s)")
                    ).forEachIndexed { idx, (spd, lbl) ->
                        FilterChip(
                            selected = abs(state.magSweepSpeedMps - spd) < 0.08f,
                            onClick = { onSweepSpeedChanged(spd) },
                            label = { Text(lbl) },
                            modifier = Modifier.testTag("sweep_speed_chip_$idx")
                        )
                    }
                }

                val stft = state.magStftResult
                if (stft != null && stft.magnitudesUt.isNotEmpty()) {
                    // Instantaneous STFT frequency bar spectrum
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF070D14))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(6.dp)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("mag_stft_spectrum_canvas")
                        ) {
                            val mags = stft.magnitudesUt
                            val maxVal = mags.maxOrNull()?.coerceAtLeast(1.5) ?: 1.5
                            val count = mags.size
                            val barW = size.width / count.coerceAtLeast(1)
                            mags.forEachIndexed { idx, v ->
                                val norm = (v / maxVal).toFloat().coerceIn(0f, 1f)
                                val barH = norm * size.height * 0.9f
                                val freq = stft.frequenciesHz.getOrElse(idx) { 0.0 }
                                val barColor = when {
                                    norm > 0.72f -> Color(0xFFFF5252)
                                    freq <= 1.5 -> Color(0xFF00E5FF)
                                    else -> Color(0xFF69F0AE)
                                }
                                drawRect(
                                    color = barColor,
                                    topLeft = Offset(idx * barW + 1f, size.height - barH),
                                    size = Size((barW - 2f).coerceAtLeast(2f), barH.coerceAtLeast(2f))
                                )
                            }
                        }
                    }

                    // Rolling STFT Waterfall Spectrogram (Time rows x Frequency bins)
                    if (state.magWaterfallSpectrogram.size >= 2) {
                        Text(
                            text = "آبشار زمانی-فرکانسی STFT (اسپکتروگرام حرکتی):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(68.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF050910))
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val rows = state.magWaterfallSpectrogram
                                val nRows = rows.size
                                val rowH = size.height / nRows.toFloat()
                                val peakGlobal = rows.flatten().maxOrNull()?.coerceAtLeast(1.5f) ?: 1.5f
                                rows.forEachIndexed { rIdx, bins ->
                                    val nCols = bins.size.coerceAtLeast(1)
                                    val colW = size.width / nCols.toFloat()
                                    val yTop = (nRows - 1 - rIdx) * rowH
                                    bins.forEachIndexed { cIdx, amp ->
                                        val norm = (amp / peakGlobal).coerceIn(0f, 1f)
                                        val heatColor = when {
                                            norm > 0.75f -> Color(0xFFFF3B5C)
                                            norm > 0.50f -> Color(0xFFFFAB00)
                                            norm > 0.25f -> Color(0xFF00E5FF)
                                            else -> Color(0xFF0D2136)
                                        }
                                        drawRect(
                                            color = heatColor,
                                            topLeft = Offset(cIdx * colW, yTop),
                                            size = Size(colW + 0.5f, rowH + 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Peak: %.2f Hz (%.2f µT) | Centroid: %.2f Hz | Low(<1.5Hz): %.0f%%\nSweep λ: %.2f m | Apparent Max Depth Env: %.2f m",
                                stft.dominantFreqHz,
                                stft.peakAmplitudeUt,
                                stft.spectralCentroidHz,
                                stft.lowBandEnergyRatio * 100.0,
                                stft.estimatedSweepWavelengthM,
                                stft.apparentMaxDepthM
                            ),
                            style = MonoTelemetryStyle,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = stft.anomalyClassLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (stft.apparentMaxDepthM > 0.0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "در حال جمع‌آوری ۱۶ نمونه متوالی مگنتومتر برای محاسبه تبدیل فوریه STFT...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 3B-2. Adaptive Depth-Based Filtering & Live Material Signature Analysis
        AdaptiveDepthFilterCard(
            depthFilterMode = state.depthFilterMode,
            adaptiveFilter = state.adaptiveDepthFilter,
            onDepthFilterModeChanged = onDepthFilterModeChanged
        )

        MaterialSignatureAnalysisCard(
            analysis = state.liveMaterialSignature
        )

        // 3C. Interactive Rotatable 3D Multi-Angle + STFT Anomaly Mesh
        if (state.fieldReceiverPins.isNotEmpty() || state.magStftResult != null) {
            Receiver3DReconstructionCard(
                pins = state.fieldReceiverPins,
                liveDeltaUt = state.magDeltaUt,
                liveDepthM = state.magStftResult?.apparentMaxDepthM ?: 0.0
            )
        }

        // 4. Recorded Multi-Angle Field Anomaly Pins
        if (state.fieldReceiverPins.isNotEmpty()) {
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
                        Text(
                            text = "پروفیل نقاط و عکس‌های ثبت‌شده (${state.fieldReceiverPins.size})",
                            style = MaterialTheme.typography.titleSmall
                        )
                        IconButton(
                            onClick = onClearPins,
                            modifier = Modifier.testTag("clear_receiver_pins_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "پاک کردن نقاط ثبت‌شده"
                            )
                        }
                    }

                    state.fieldReceiverPins.forEach { pin ->
                        ReceiverPinCard(
                            pin = pin,
                            onLockPinOnMap = onLockPinOnMap,
                            onSendPinToAi = onSendPinToAi
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ReceiverPinCard(
    pin: FieldAnomalyPin,
    onLockPinOnMap: (GeoPoint) -> Unit,
    onSendPinToAi: (Bitmap, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            pin.snapshot?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "فریم ثبت‌شده مگنتومتر",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "زاویه ثبت: ${pin.angleTag}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "|B|=%.1f µT (ΔB %+.1f µT | Z=%.1fσ)",
                            pin.magnitudeUt,
                            pin.deltaUt,
                            pin.zScore
                        ),
                        style = MonoTelemetryStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (pin.latitude != null && pin.longitude != null) {
                            String.format(Locale.US, "GPS: %.6f, %.6f | Yaw %.0f°", pin.latitude, pin.longitude, pin.yawDeg)
                        } else {
                            String.format(Locale.US, "No GPS lock | Yaw %.0f°", pin.yawDeg)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (pin.latitude != null && pin.longitude != null) {
                OutlinedButton(
                    onClick = { onLockPinOnMap(GeoPoint(pin.latitude, pin.longitude)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AddLocationAlt, contentDescription = "قفل روی نقشه", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("قفل روی نقشه")
                }
            }
            if (pin.snapshot != null) {
                OutlinedButton(
                    onClick = {
                        val prompt = String.format(
                            Locale.US,
                            "فریم میدانی زاویه [%s] همراه با مگنتومتر و GPS ثبت شده: |B|=%.1f µT، ΔB=%+.1f µT (Z=%.2fσ)، آزیموت %.0f°%s. بافت خاک/سنگ، رطوبت سطحی و دلیل احتمالی ناهنجاری را تحلیل کن.",
                            pin.angleTag,
                            pin.magnitudeUt,
                            pin.deltaUt,
                            pin.zScore,
                            pin.yawDeg,
                            if (pin.latitude != null && pin.longitude != null) {
                                String.format(Locale.US, "، مختصات %.6f, %.6f", pin.latitude, pin.longitude)
                            } else ""
                        )
                        onSendPinToAi(pin.snapshot, prompt)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال به هوش مصنوعی", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("ارسال به هوش")
                }
            }
        }
    }
}

@Composable
private fun Receiver3DReconstructionCard(
    pins: List<FieldAnomalyPin>,
    liveDeltaUt: Double,
    liveDepthM: Double
) {
    var rotYawDeg by remember { mutableFloatStateOf(32f) }
    var rotPitchDeg by remember { mutableFloatStateOf(28f) }

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
                Text(
                    text = "مدل سه‌بعدی چرخشی اثر سطحی (تجمیع ۵ زاویه + STFT)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "بکشید تا بچرخد",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF060B12))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            rotYawDeg = (rotYawDeg + dragAmount.x * 0.45f) % 360f
                            rotPitchDeg = (rotPitchDeg - dragAmount.y * 0.35f).coerceIn(8f, 78f)
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("receiver_3d_reconstruction_canvas")
                ) {
                    val yawRad = rotYawDeg * (PI / 180.0)
                    val pitchRad = rotPitchDeg * (PI / 180.0)
                    val cosY = cos(yawRad)
                    val sinY = sin(yawRad)
                    val cosP = cos(pitchRad)
                    val sinP = sin(pitchRad)
                    val scale = min(size.width, size.height) * 0.34f
                    val cx = size.width / 2f
                    val cy = size.height * 0.44f

                    fun project(x: Double, y: Double, z: Double): Offset {
                        val x1 = x * cosY - y * sinY
                        val y1 = x * sinY + y * cosY
                        val y2 = y1 * cosP - z * sinP
                        val z2 = y1 * sinP + z * cosP
                        val factor = 3.2 / (3.2 + y2 * 0.45)
                        return Offset(
                            x = (cx + x1 * scale * factor).toFloat(),
                            y = (cy - z2 * scale * factor).toFloat()
                        )
                    }

                    val pinNodes = listOf(
                        Triple(0.0, 0.0, pins.firstOrNull { it.angleTag.contains("مرکز") }),
                        Triple(0.0, 0.65, pins.firstOrNull { it.angleTag.contains("شمال") }),
                        Triple(0.65, 0.0, pins.firstOrNull { it.angleTag.contains("شرق") }),
                        Triple(0.0, -0.65, pins.firstOrNull { it.angleTag.contains("جنوب") }),
                        Triple(-0.65, 0.0, pins.firstOrNull { it.angleTag.contains("غرب") })
                    )

                    fun evalFieldDepth(gx: Double, gy: Double): Pair<Double, Double> {
                        var deltaSum = 0.0
                        var depthSum = 0.0
                        pinNodes.forEach { (nx, ny, p) ->
                            val d = if (p != null) abs(p.deltaUt) else if (nx == 0.0 && ny == 0.0) abs(liveDeltaUt) else 0.0
                            val dep = p?.apparentDepthM ?: if (nx == 0.0 && ny == 0.0) liveDepthM else 0.0
                            val r2 = (gx - nx) * (gx - nx) + (gy - ny) * (gy - ny)
                            val w = exp(-r2 / 0.24)
                            deltaSum += d * w
                            depthSum += dep * w
                        }
                        return Pair(deltaSum, depthSum)
                    }

                    val gridN = 8
                    val surfacePts = Array(gridN) { Array(gridN) { Offset.Zero } }
                    val depthPts = Array(gridN) { Array(gridN) { Offset.Zero } }
                    val intensityGrid = Array(gridN) { DoubleArray(gridN) }

                    for (iy in 0 until gridN) {
                        val gy = -1.0 + 2.0 * iy / (gridN - 1)
                        for (ix in 0 until gridN) {
                            val gx = -1.0 + 2.0 * ix / (gridN - 1)
                            val (deltaVal, depthVal) = evalFieldDepth(gx, gy)
                            intensityGrid[iy][ix] = deltaVal
                            val zSurf = (deltaVal / 35.0).coerceIn(0.0, 0.45)
                            val zSub = -(depthVal / 1.5).coerceIn(0.05, 0.72)
                            surfacePts[iy][ix] = project(gx, gy, zSurf)
                            depthPts[iy][ix] = project(gx, gy, zSub)
                        }
                    }

                    for (iy in 0 until gridN) {
                        for (ix in 0 until gridN) {
                            val p0 = depthPts[iy][ix]
                            val col = if (intensityGrid[iy][ix] > 12.0) Color(0xFFFF5252) else Color(0xFF00E5FF)
                            if (ix + 1 < gridN) {
                                drawLine(col.copy(alpha = 0.45f), p0, depthPts[iy][ix + 1], strokeWidth = 1.4f)
                            }
                            if (iy + 1 < gridN) {
                                drawLine(col.copy(alpha = 0.45f), p0, depthPts[iy + 1][ix], strokeWidth = 1.4f)
                            }
                        }
                    }

                    for (iy in 0 until gridN) {
                        for (ix in 0 until gridN) {
                            val s0 = surfacePts[iy][ix]
                            val amp = intensityGrid[iy][ix]
                            val sCol = when {
                                amp > 18.0 -> Color(0xFFFF3B5C)
                                amp > 7.0 -> Color(0xFFFFAB00)
                                else -> Color(0xFF69F0AE)
                            }
                            if (ix + 1 < gridN) {
                                drawLine(sCol.copy(alpha = 0.8f), s0, surfacePts[iy][ix + 1], strokeWidth = 1.8f)
                            }
                            if (iy + 1 < gridN) {
                                drawLine(sCol.copy(alpha = 0.8f), s0, surfacePts[iy + 1][ix], strokeWidth = 1.8f)
                            }
                            if (amp > 8.0) {
                                drawLine(Color(0x88FFAB00), s0, depthPts[iy][ix], strokeWidth = 1.2f)
                            }
                        }
                    }

                    pinNodes.forEach { (nx, ny, p) ->
                        if (p != null) {
                            val topPt = project(nx, ny, (abs(p.deltaUt) / 35.0).coerceIn(0.0, 0.45))
                            val botPt = project(nx, ny, -((p.apparentDepthM ?: liveDepthM) / 1.5).coerceIn(0.05, 0.72))
                            drawLine(Color.White.copy(alpha = 0.75f), topPt, botPt, strokeWidth = 2f)
                            drawCircle(Color(0xFFFFEA00), radius = 5.5f, center = topPt)
                            drawCircle(Color(0xFFFF3B5C), radius = 4.5f, center = botPt)
                        }
                    }
                }
            }
        }
    }
}
