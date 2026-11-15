package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AiChatMessage
import com.example.data.AiEndpointConfig
import com.example.data.AiExperienceEntry
import com.example.data.ArchaeologyAiRepository
import com.example.data.CustomOpenEndpointArchaeologyAiRepository
import com.example.data.RadarAnalysisResult
import com.example.data.RadarRepository
import com.example.data.TargetImageryBundle
import com.example.domain.AdaptiveDepthFilter
import com.example.domain.AdaptiveDepthFilterResult
import com.example.domain.DetectedRadarPoint
import com.example.domain.FieldAnomalyPin
import com.example.domain.FieldCompanionWatchdog
import com.example.domain.FieldReceiverMath
import com.example.domain.GeoPoint
import com.example.domain.GeodeticOffsetResult
import com.example.domain.GridCellAnalysis
import com.example.domain.MagStftResult
import com.example.domain.MagnetometerReading
import com.example.domain.MaterialSignatureAnalysis
import com.example.domain.MaterialSpectralSignatureProcessor
import com.example.domain.OpticalGridStats
import com.example.domain.QuaternionData
import com.example.domain.RadarMathEngine
import com.example.domain.ThermalStylePixelResult
import com.example.sensors.LiveGpsFix
import com.example.sensors.LiveTelemetryManager
import com.example.sensors.VoiceNavigationFormatter
import com.example.sensors.VoiceNavigationService
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface ScanState {
    data object Idle : ScanState
    data object Loading : ScanState
    data class Success(val result: RadarAnalysisResult) : ScanState
    data class Error(val message: String) : ScanState
}

enum class OffsetMode {
    FORWARD_FROM_CENTER,
    REVERSE_FROM_GROUND
}

data class RadarUiState(
    val hasLocationPermission: Boolean = false,
    val liveGps: LiveGpsFix? = null,
    val isAveragingGps: Boolean = false,
    val gpsAverageSampleCount: Int = 0,
    val gpsAverageTargetSamples: Int = 20,
    val averagedGpsLat: Double? = null,
    val averagedGpsLon: Double? = null,
    val averagedGpsAlt: Double? = null,
    val averagedGpsAccuracyM: Float? = null,
    val quaternion: QuaternionData = QuaternionData(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    val hasRotationSensor: Boolean = true,
    val latInput: String = "35.1709667",
    val lonInput: String = "49.6665633",
    val radiusMeters: Float = 500f,
    val scanState: ScanState = ScanState.Idle,
    val selectedCell: GridCellAnalysis? = null,
    val selectedTargetLabel: String? = null,
    val rawTargetPointZero: GeoPoint? = null,
    val targetPointZero: GeoPoint? = null,
    val gcpEnabled: Boolean = false,
    val gcpImageLatInput: String = "",
    val gcpImageLonInput: String = "",
    val gcpTrueLatInput: String = "",
    val gcpTrueLonInput: String = "",
    val gcpShiftEastM: Double = 0.0,
    val gcpShiftNorthM: Double = 0.0,
    val audioGuidanceEnabled: Boolean = false,
    val ttsGuidanceEnabled: Boolean = true,
    val isTtsReady: Boolean = false,
    val lastSpokenCue: String? = null,
    val liveGuidanceOffset: GeodeticOffsetResult? = null,
    val groundLatInput: String = "35.1709667",
    val groundLonInput: String = "49.6665633",
    val offsetMode: OffsetMode = OffsetMode.FORWARD_FROM_CENTER,
    val deltaEastInput: String = "45.0",
    val deltaNorthInput: String = "-30.0",
    val calculatorOffsetResult: GeodeticOffsetResult? = null,
    val computedReverseTarget: GeoPoint? = null,
    val showMethodologyDialog: Boolean = false,
    val targetImagery: TargetImageryBundle? = null,
    val isLoadingTargetImagery: Boolean = false,
    val targetImageryError: String? = null,
    val aiConfig: AiEndpointConfig = AiEndpointConfig(),
    val aiTrainingNotes: String = "",
    val aiExperiences: List<AiExperienceEntry> = emptyList(),
    val aiMessages: List<AiChatMessage> = emptyList(),
    val aiPromptInput: String = "",
    val aiAttachedImage: Bitmap? = null,
    val isAiLoading: Boolean = false,
    val aiError: String? = null,
    val aiVoiceReplyEnabled: Boolean = true,
    val hasMagnetometer: Boolean = true,
    val magnetometerReading: MagnetometerReading? = null,
    val magBaselineMeanUt: Double? = null,
    val magBaselineStdUt: Double = 0.6,
    val isCalibratingMagBaseline: Boolean = false,
    val magCalibrationCount: Int = 0,
    val magDeltaUt: Double = 0.0,
    val magZScore: Double = 0.0,
    val magHistoryUt: List<Double> = emptyList(),
    val magSampleRateHz: Double = 25.0,
    val magSweepSpeedMps: Float = 0.40f,
    val magStftResult: MagStftResult? = null,
    val magWaterfallSpectrogram: List<List<Float>> = emptyList(),
    val magSensitivityThresholdUt: Float = 8f,
    val magAudioEnabled: Boolean = false,
    val isMagBaselineCalibrated: Boolean = false,
    val magConsecutiveAlertCount: Int = 0,
    val magConsecutiveSign: Int = 0,
    val magEffectiveFloorUt: Double = 8.0,
    val isMagConfirmedAlert: Boolean = false,
    val indoorInterferenceWarning: String? = null,
    val isReceiverCameraActive: Boolean = false,
    val opticalConsecutiveAlertCount: Int = 0,
    val isOpticalConfirmedAlert: Boolean = false,
    val lastOpticalMeta: ThermalStylePixelResult? = null,
    val confirmedDetectionLevel: Int = 0,
    val confirmedAlertTitle: String = "قفل ضدخطا: در حال کالیبراسیون صفر محیط",
    val confirmedAlertDetail: String = "برای جلوگیری از تشخیص اشتباه داخل خانه، چند ثانیه گوشی را ثابت و دور از آهن نگه دارید.",
    val autoDetectionSoundEnabled: Boolean = true,
    val ramUsagePercent: Int = 0,
    val ramUsedMb: Int = 0,
    val ramMaxMb: Int = 0,
    val lastAutoRamCleanAtMs: Long? = null,
    val lastAutoRamCleanStatus: String? = null,
    val linearAccelMps2: Double = 0.0,
    val ambientLightLux: Float? = null,
    val baroPressureHpa: Float? = null,
    val receiverOpticalOverlayMode: Int = 1,
    val receiverCaptureAngleIndex: Int = 0,
    val fieldReceiverPins: List<FieldAnomalyPin> = emptyList(),
    val depthFilterMode: Int = 0,
    val adaptiveDepthFilter: AdaptiveDepthFilterResult? = null,
    val liveMaterialSignature: MaterialSignatureAnalysis? = null,
    val aiWatchdogEnabled: Boolean = false,
    val isAiCompanionSheetOpen: Boolean = false,
    val liveReceiverFrameBitmap: Bitmap? = null,
    val companionAttachedImage: Bitmap? = null,
    val companionLastReply: String? = null,
    val companionError: String? = null,
    val isCompanionLoading: Boolean = false,
    val watchdogAlertTitle: String? = null,
    val watchdogAlertMessage: String? = null,
    val watchdogCloudVerdict: String? = null
)

class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RadarRepository()
    private val aiRepository: ArchaeologyAiRepository = CustomOpenEndpointArchaeologyAiRepository(application)
    private val telemetry = LiveTelemetryManager(application)
    private val voiceNav = VoiceNavigationService(application)

    private val _uiState = MutableStateFlow(
        RadarUiState(
            aiConfig = aiRepository.loadConfig(),
            aiTrainingNotes = aiRepository.loadTrainingNotes(),
            aiExperiences = aiRepository.loadExperiences()
        )
    )
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var audioLoopJob: Job? = null
    private var magAudioLoopJob: Job? = null
    private var lastSpokenAtMs = 0L
    private var lastSpokenDistM = Double.NaN
    private var lastSpokenTurnDeg = Double.NaN
    private var wasLockedAnnounced = false

    private val magCalibSamples = mutableListOf<Double>()
    private val magTimestampsMs = ArrayDeque<Long>(64)
    private var magSamplesSinceStft = 0
    private var lastConfirmedAlertSoundAtMs = 0L
    private var lastAlertLevelTriggered = 0
    private val adaptiveDepthFilter = AdaptiveDepthFilter()
    private val materialSpectralProcessor = MaterialSpectralSignatureProcessor()
    private val fieldWatchdog = FieldCompanionWatchdog()
    private var lastWatchdogCloudConsultAtMs = 0L
    private var isWatchdogCloudConsultRunning = false

    private var gpsWeightSum = 0.0
    private var gpsWeightedLatSum = 0.0
    private var gpsWeightedLonSum = 0.0
    private var gpsWeightedAltSum = 0.0
    private var gpsAccSqSum = 0.0
    private var gpsSampleCount = 0

    init {
        telemetry.startOrientationSensor()
        viewModelScope.launch {
            while (isActive) {
                refreshAndAutoTrimMemory()
                delay(2000L)
            }
        }

        viewModelScope.launch {
            voiceNav.isReady.collect { ready ->
                _uiState.update { it.copy(isTtsReady = ready) }
            }
        }

        viewModelScope.launch {
            telemetry.hasRotationSensor.collect { available ->
                _uiState.update { it.copy(hasRotationSensor = available) }
            }
        }

        viewModelScope.launch {
            telemetry.quaternion.collect { quat ->
                _uiState.update { it.copy(quaternion = quat) }
            }
        }

        viewModelScope.launch {
            telemetry.hasMagnetometer.collect { available ->
                _uiState.update { it.copy(hasMagnetometer = available) }
            }
        }

        viewModelScope.launch {
            telemetry.linearAccelMagMps2.collect { acc ->
                _uiState.update { it.copy(linearAccelMps2 = acc) }
            }
        }

        viewModelScope.launch {
            telemetry.ambientLightLux.collect { lux ->
                _uiState.update { it.copy(ambientLightLux = lux) }
            }
        }

        viewModelScope.launch {
            telemetry.baroPressureHpa.collect { hpa ->
                _uiState.update { it.copy(baroPressureHpa = hpa) }
            }
        }

        viewModelScope.launch {
            telemetry.magnetometerReading.collect { reading ->
                if (reading != null) {
                    magTimestampsMs.addLast(reading.timestampMs)
                    while (magTimestampsMs.size > 64) magTimestampsMs.removeFirst()
                    val computedRateHz = if (magTimestampsMs.size >= 6) {
                        val dtMs = (magTimestampsMs.last() - magTimestampsMs.first()).coerceAtLeast(100L)
                        ((magTimestampsMs.size - 1) * 1000.0 / dtMs.toDouble()).coerceIn(5.0, 55.0)
                    } else 25.0
                    magSamplesSinceStft++
                    val shouldUpdateStft = magSamplesSinceStft >= 3 || _uiState.value.magStftResult == null
                    if (shouldUpdateStft) magSamplesSinceStft = 0

                    var triggeredAlertLevel = 0
                    _uiState.update { st ->
                        var nextCalib = st.isCalibratingMagBaseline
                        var nextBaselineMean = st.magBaselineMeanUt
                        var nextBaselineStd = st.magBaselineStdUt
                        var nextCalibCount = st.magCalibrationCount
                        var nextCalibrated = st.isMagBaselineCalibrated
                        var nextWarning = st.indoorInterferenceWarning
                        var nextFloor = st.magEffectiveFloorUt

                        if (nextBaselineMean == null && !nextCalib) {
                            nextBaselineMean = reading.magnitudeUt
                        }

                        if (nextCalib) {
                            magCalibSamples.add(reading.magnitudeUt)
                            nextCalibCount = magCalibSamples.size
                            if (magCalibSamples.size >= 25) {
                                val envCheck = FieldReceiverMath.evaluateMagnetometerCalibrationWindow(
                                    samplesUt = magCalibSamples,
                                    userThresholdUt = st.magSensitivityThresholdUt
                                )
                                nextBaselineMean = envCheck.meanUt
                                nextBaselineStd = envCheck.stdDevUt.coerceAtLeast(0.35)
                                nextCalibrated = true
                                nextCalib = false
                                nextWarning = envCheck.warningReason
                                nextFloor = envCheck.effectiveFloorUt
                            }
                        }

                        val base = nextBaselineMean ?: reading.magnitudeUt
                        val (delta, zScore) = FieldReceiverMath.computeAnomaly(
                            magnitudeUt = reading.magnitudeUt,
                            baselineMeanUt = base,
                            baselineStdUt = nextBaselineStd
                        )

                        val updatedHistory = (st.magHistoryUt + reading.magnitudeUt).takeLast(64)

                        val nextStft = if (shouldUpdateStft && updatedHistory.size >= 16) {
                            FieldReceiverMath.computeMagnetometerStft(
                                samplesUt = updatedHistory,
                                sampleRateHz = computedRateHz,
                                sweepSpeedMps = st.magSweepSpeedMps.toDouble(),
                                currentDeltaUt = delta,
                                baselineStdUt = nextBaselineStd
                            )
                        } else st.magStftResult

                        val nextWaterfall = if (shouldUpdateStft && nextStft != null && nextStft.magnitudesUt.isNotEmpty()) {
                            val row = nextStft.magnitudesUt.map { it.toFloat() }
                            (st.magWaterfallSpectrogram + listOf(row)).takeLast(18)
                        } else st.magWaterfallSpectrogram

                        val depthFilter = adaptiveDepthFilter.filterAndIsolateDeepSignal(
                            samplesUt = updatedHistory,
                            baselineMeanUt = base,
                            baselineStdUt = nextBaselineStd,
                            stft = nextStft,
                            opticalMeta = st.lastOpticalMeta,
                            userBaseThresholdUt = st.magSensitivityThresholdUt,
                            depthBandMode = st.depthFilterMode,
                            sweepSpeedMps = st.magSweepSpeedMps.toDouble()
                        )

                        val materialSig = if (shouldUpdateStft || st.liveMaterialSignature == null) {
                            materialSpectralProcessor.analyzeLiveSignature(
                                stft = nextStft,
                                depthFilter = depthFilter,
                                baselineStdUt = nextBaselineStd,
                                opticalMeta = st.lastOpticalMeta
                            )
                        } else st.liveMaterialSignature

                        val isLevel = FieldReceiverMath.isPhoneLevelForGroundSweep(
                            pitchDeg = st.quaternion.pitchDeg,
                            rollDeg = st.quaternion.rollDeg,
                            maxTiltDeg = 18.0
                        )
                        val adaptedFloor = if (nextFloor > st.magSensitivityThresholdUt.toDouble() + 1.0) {
                            // Preserve indoor EM safety elevation if indoor rebar/hum detected
                            maxOf(depthFilter.adaptiveThresholdUt, nextFloor)
                        } else {
                            depthFilter.adaptiveThresholdUt
                        }
                        val thresholdToBeat = maxOf(
                            adaptedFloor,
                            nextBaselineStd * 3.4
                        )
                        val effectiveSignal = depthFilter.activeSignalUt
                        val effectiveZ = abs(effectiveSignal) / nextBaselineStd.coerceAtLeast(0.35)
                        val isSteadyHand = st.linearAccelMps2 <= 2.3
                        val curSign = if (effectiveSignal >= 0.0) 1 else -1
                        val rawExceeds = nextCalibrated && isLevel && isSteadyHand &&
                            !depthFilter.surfaceClutterSuppressed &&
                            abs(effectiveSignal) >= thresholdToBeat && effectiveZ >= 3.4

                        val nextConsec = if (rawExceeds) {
                            if (st.magConsecutiveSign == curSign || st.magConsecutiveAlertCount == 0) {
                                (st.magConsecutiveAlertCount + 1).coerceAtMost(30)
                            } else 1
                        } else 0
                        val nextSign = if (rawExceeds) curSign else 0
                        val magConfirmed = nextConsec >= 5

                        if (nextCalibrated && !nextCalib && (reading.magnitudeUt < 20.0 || reading.magnitudeUt > 72.0)) {
                            nextWarning = String.format(
                                Locale.US,
                                "میدان محیط غیرعادی است (|B|=%.0f µT) — نزدیک فلز/سیم‌کشی ساختمان هستید.",
                                reading.magnitudeUt
                            )
                        } else if (nextCalibrated && nextFloor <= st.magSensitivityThresholdUt + 2.0 && reading.magnitudeUt in 22.0..68.0) {
                            nextWarning = null
                        }

                        val verdict = FieldReceiverMath.evaluateDetectionAlert(
                            isMagBaselineCalibrated = nextCalibrated,
                            isMagConfirmed = magConfirmed,
                            isOpticalConfirmed = st.isOpticalConfirmedAlert,
                            isCameraActive = st.isReceiverCameraActive,
                            magDeltaUt = effectiveSignal,
                            opticalMeta = st.lastOpticalMeta,
                            indoorWarning = nextWarning
                        )
                        triggeredAlertLevel = verdict.level

                        st.copy(
                            magnetometerReading = reading,
                            magBaselineMeanUt = nextBaselineMean,
                            magBaselineStdUt = nextBaselineStd,
                            isCalibratingMagBaseline = nextCalib,
                            magCalibrationCount = nextCalibCount,
                            isMagBaselineCalibrated = nextCalibrated,
                            magConsecutiveAlertCount = nextConsec,
                            magConsecutiveSign = nextSign,
                            magEffectiveFloorUt = nextFloor,
                            isMagConfirmedAlert = magConfirmed,
                            indoorInterferenceWarning = nextWarning,
                            confirmedDetectionLevel = verdict.level,
                            confirmedAlertTitle = verdict.title,
                            confirmedAlertDetail = verdict.detail,
                            magDeltaUt = delta,
                            magZScore = zScore,
                            magHistoryUt = updatedHistory,
                            magSampleRateHz = computedRateHz,
                            magStftResult = nextStft,
                            magWaterfallSpectrogram = nextWaterfall,
                            adaptiveDepthFilter = depthFilter,
                            liveMaterialSignature = materialSig
                        )
                    }
                    maybeTriggerDetectionAlertTone(triggeredAlertLevel)
                    evaluateWatchdogIfPermitted()
                }
            }
        }

        viewModelScope.launch {
            telemetry.gpsFix.collect { fix ->
                if (fix != null) {
                    _uiState.update { state ->
                        var nextIsAveraging = state.isAveragingGps
                        var nextCount = state.gpsAverageSampleCount
                        var nextAvgLat = state.averagedGpsLat
                        var nextAvgLon = state.averagedGpsLon
                        var nextAvgAlt = state.averagedGpsAlt
                        var nextAvgAcc = state.averagedGpsAccuracyM

                        if (state.isAveragingGps) {
                            val acc = fix.accuracyM.coerceAtLeast(1.0f).toDouble()
                            val w = 1.0 / (acc * acc)
                            gpsWeightSum += w
                            gpsWeightedLatSum += fix.latitude * w
                            gpsWeightedLonSum += fix.longitude * w
                            gpsWeightedAltSum += fix.altitudeM * w
                            gpsAccSqSum += acc * acc
                            gpsSampleCount += 1

                            nextCount = gpsSampleCount
                            nextAvgLat = gpsWeightedLatSum / gpsWeightSum
                            nextAvgLon = gpsWeightedLonSum / gpsWeightSum
                            nextAvgAlt = gpsWeightedAltSum / gpsWeightSum
                            val rmsAcc = kotlin.math.sqrt(gpsAccSqSum / gpsSampleCount)
                            nextAvgAcc = (rmsAcc / kotlin.math.sqrt(gpsSampleCount.toDouble())).toFloat()

                            if (gpsSampleCount >= state.gpsAverageTargetSamples) {
                                nextIsAveraging = false
                            }
                        }

                        val target = state.targetPointZero
                        val liveOffset = if (target != null) {
                            RadarMathEngine.computeGeodeticOffset(
                                fromLat = fix.latitude,
                                fromLon = fix.longitude,
                                toLat = target.latitude,
                                toLon = target.longitude,
                                fromElevationM = fix.altitudeM,
                                toElevationM = state.selectedCell?.rawElevationM
                            )
                        } else {
                            null
                        }

                        state.copy(
                            liveGps = fix,
                            isAveragingGps = nextIsAveraging,
                            gpsAverageSampleCount = nextCount,
                            averagedGpsLat = nextAvgLat,
                            averagedGpsLon = nextAvgLon,
                            averagedGpsAlt = nextAvgAlt,
                            averagedGpsAccuracyM = nextAvgAcc,
                            liveGuidanceOffset = liveOffset
                        )
                    }
                    evaluateWatchdogIfPermitted()
                }
            }
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasLocationPermission = granted) }
        if (granted) {
            telemetry.startLocationUpdates()
        }
    }

    fun toggleStaticGpsAveraging() {
        val currentlyAveraging = _uiState.value.isAveragingGps
        if (currentlyAveraging) {
            _uiState.update { it.copy(isAveragingGps = false) }
            return
        }
        gpsWeightSum = 0.0
        gpsWeightedLatSum = 0.0
        gpsWeightedLonSum = 0.0
        gpsWeightedAltSum = 0.0
        gpsAccSqSum = 0.0
        gpsSampleCount = 0

        val currentFix = _uiState.value.liveGps
        if (currentFix != null) {
            val acc = currentFix.accuracyM.coerceAtLeast(1.0f).toDouble()
            val w = 1.0 / (acc * acc)
            gpsWeightSum = w
            gpsWeightedLatSum = currentFix.latitude * w
            gpsWeightedLonSum = currentFix.longitude * w
            gpsWeightedAltSum = currentFix.altitudeM * w
            gpsAccSqSum = acc * acc
            gpsSampleCount = 1
        }

        _uiState.update {
            it.copy(
                isAveragingGps = true,
                gpsAverageSampleCount = gpsSampleCount,
                averagedGpsLat = if (gpsSampleCount > 0) currentFix?.latitude else null,
                averagedGpsLon = if (gpsSampleCount > 0) currentFix?.longitude else null,
                averagedGpsAlt = if (gpsSampleCount > 0) currentFix?.altitudeM else null,
                averagedGpsAccuracyM = if (gpsSampleCount > 0) currentFix?.accuracyM else null
            )
        }
    }

    fun applyAveragedGpsToScanCenter() {
        val st = _uiState.value
        val lat = st.averagedGpsLat ?: st.liveGps?.latitude ?: return
        val lon = st.averagedGpsLon ?: st.liveGps?.longitude ?: return
        _uiState.update {
            it.copy(
                latInput = String.format(Locale.US, "%.7f", lat),
                lonInput = String.format(Locale.US, "%.7f", lon),
                groundLatInput = String.format(Locale.US, "%.7f", lat),
                groundLonInput = String.format(Locale.US, "%.7f", lon)
            )
        }
    }

    fun applyAveragedGpsToGcpTrue() {
        val st = _uiState.value
        val lat = st.averagedGpsLat ?: st.liveGps?.latitude ?: return
        val lon = st.averagedGpsLon ?: st.liveGps?.longitude ?: return
        _uiState.update {
            it.copy(
                gcpTrueLatInput = String.format(Locale.US, "%.7f", lat),
                gcpTrueLonInput = String.format(Locale.US, "%.7f", lon)
            )
        }
        recalculateGcpShiftAndTarget()
    }

    fun fillGcpImageFromCurrentTarget() {
        val st = _uiState.value
        val pt = st.rawTargetPointZero ?: st.targetPointZero ?: return
        _uiState.update {
            it.copy(
                gcpImageLatInput = String.format(Locale.US, "%.7f", pt.latitude),
                gcpImageLonInput = String.format(Locale.US, "%.7f", pt.longitude)
            )
        }
        recalculateGcpShiftAndTarget()
    }

    fun onGcpImageLatChanged(value: String) {
        _uiState.update { it.copy(gcpImageLatInput = value) }
        recalculateGcpShiftAndTarget()
    }

    fun onGcpImageLonChanged(value: String) {
        _uiState.update { it.copy(gcpImageLonInput = value) }
        recalculateGcpShiftAndTarget()
    }

    fun onGcpTrueLatChanged(value: String) {
        _uiState.update { it.copy(gcpTrueLatInput = value) }
        recalculateGcpShiftAndTarget()
    }

    fun onGcpTrueLonChanged(value: String) {
        _uiState.update { it.copy(gcpTrueLonInput = value) }
        recalculateGcpShiftAndTarget()
    }

    fun setGcpEnabled(enabled: Boolean) {
        _uiState.update { it.copy(gcpEnabled = enabled) }
        recalculateGcpShiftAndTarget()
    }

    private fun recalculateGcpShiftAndTarget() {
        _uiState.update { state ->
            val imgLat = state.gcpImageLatInput.trim().toDoubleOrNull()
            val imgLon = state.gcpImageLonInput.trim().toDoubleOrNull()
            val trueLat = state.gcpTrueLatInput.trim().toDoubleOrNull()
            val trueLon = state.gcpTrueLonInput.trim().toDoubleOrNull()

            val (shiftE, shiftN) = if (imgLat != null && imgLon != null && trueLat != null && trueLon != null) {
                val off = RadarMathEngine.computeGeodeticOffset(
                    fromLat = imgLat,
                    fromLon = imgLon,
                    toLat = trueLat,
                    toLon = trueLon
                )
                Pair(off.deltaEastMeters, off.deltaNorthMeters)
            } else {
                Pair(0.0, 0.0)
            }

            val calibratedTarget = state.rawTargetPointZero?.let { raw ->
                if (state.gcpEnabled) {
                    RadarMathEngine.offsetCoordinate(raw.latitude, raw.longitude, shiftE, shiftN)
                } else {
                    raw
                }
            }

            val liveOffset = if (calibratedTarget != null && state.liveGps != null) {
                RadarMathEngine.computeGeodeticOffset(
                    fromLat = state.liveGps.latitude,
                    fromLon = state.liveGps.longitude,
                    toLat = calibratedTarget.latitude,
                    toLon = calibratedTarget.longitude,
                    fromElevationM = state.liveGps.altitudeM,
                    toElevationM = state.selectedCell?.rawElevationM
                )
            } else {
                state.liveGuidanceOffset
            }

            state.copy(
                gcpShiftEastM = shiftE,
                gcpShiftNorthM = shiftN,
                targetPointZero = calibratedTarget ?: state.targetPointZero,
                liveGuidanceOffset = liveOffset
            )
        }
        recalculateCalculatorOffset()
    }

    private fun applyGcpIfEnabled(raw: GeoPoint, state: RadarUiState): GeoPoint {
        return if (state.gcpEnabled) {
            RadarMathEngine.offsetCoordinate(
                raw.latitude,
                raw.longitude,
                state.gcpShiftEastM,
                state.gcpShiftNorthM
            )
        } else {
            raw
        }
    }

    fun onLatChanged(value: String) {
        _uiState.update { it.copy(latInput = value) }
    }

    fun onLonChanged(value: String) {
        _uiState.update { it.copy(lonInput = value) }
    }

    fun onRadiusChanged(radiusM: Float) {
        _uiState.update { it.copy(radiusMeters = radiusM) }
    }

    fun fillInputsFromLiveGps() {
        val st = _uiState.value
        val lat = st.averagedGpsLat ?: st.liveGps?.latitude ?: return
        val lon = st.averagedGpsLon ?: st.liveGps?.longitude ?: return
        _uiState.update {
            it.copy(
                latInput = String.format(Locale.US, "%.7f", lat),
                lonInput = String.format(Locale.US, "%.7f", lon),
                groundLatInput = String.format(Locale.US, "%.7f", lat),
                groundLonInput = String.format(Locale.US, "%.7f", lon)
            )
        }
    }

    fun runLiveScan() {
        val lat = _uiState.value.latInput.trim().toDoubleOrNull()
        val lon = _uiState.value.lonInput.trim().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -89.9..89.9 || lon !in -180.0..180.0) {
            _uiState.update {
                it.copy(scanState = ScanState.Error("لطفاً عرض و طول جغرافیایی معتبر وارد کنید."))
            }
            return
        }

        val radius = _uiState.value.radiusMeters.toDouble()
        _uiState.update { it.copy(scanState = ScanState.Loading) }

        viewModelScope.launch {
            try {
                val result = repository.runLiveAnalysis(lat, lon, radius)
                val peakCell = result.matrixSummary.peakAnomalyCell

                val topPriority = result.fusionResult.priorityTargets.firstOrNull()
                val topMahal = result.fusionResult.mahalanobisHotspots.firstOrNull()
                val topCleanRadar = result.fusionResult.cleanRadarPoints.firstOrNull()

                val (rawTarget, targetLabel) = when {
                    topPriority != null -> Pair(
                        GeoPoint(topPriority.latitude, topPriority.longitude),
                        "اولویت اول (تلاقی رادار + کربنات/ساروج)"
                    )
                    topMahal != null -> Pair(
                        GeoPoint(topMahal.latitude, topMahal.longitude),
                        "کانون ماهالانوبیس ۵بعدی + ساویتسکی-گولای"
                    )
                    topCleanRadar != null -> Pair(
                        GeoPoint(topCleanRadar.latitude, topCleanRadar.longitude),
                        "هدف رادار CFAR پاکسازی‌شده"
                    )
                    else -> Pair(
                        GeoPoint(peakCell.latitude, peakCell.longitude),
                        "کانون ناهنجاری ماهالانوبیس (DEM)"
                    )
                }

                val st = _uiState.value
                val calibratedTarget = applyGcpIfEnabled(rawTarget, st)
                val liveFix = st.liveGps
                val liveOffset = liveFix?.let {
                    RadarMathEngine.computeGeodeticOffset(
                        fromLat = it.latitude,
                        fromLon = it.longitude,
                        toLat = calibratedTarget.latitude,
                        toLon = calibratedTarget.longitude,
                        fromElevationM = it.altitudeM,
                        toElevationM = peakCell.rawElevationM
                    )
                }

                _uiState.update {
                    it.copy(
                        scanState = ScanState.Success(result),
                        selectedCell = peakCell,
                        selectedTargetLabel = targetLabel,
                        rawTargetPointZero = rawTarget,
                        targetPointZero = calibratedTarget,
                        liveGuidanceOffset = liveOffset
                    )
                }
                recalculateCalculatorOffset()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        scanState = ScanState.Error(
                            "خطا در دریافت داده‌های زنده: ${e.localizedMessage ?: "لطفاً اتصال اینترنت را بررسی کنید"}"
                        )
                    )
                }
            }
        }
    }

    fun selectGridCell(cell: GridCellAnalysis) {
        val rawTarget = GeoPoint(cell.latitude, cell.longitude)
        val st = _uiState.value
        val calibratedTarget = applyGcpIfEnabled(rawTarget, st)
        val liveFix = st.liveGps
        val liveOffset = liveFix?.let {
            RadarMathEngine.computeGeodeticOffset(
                fromLat = it.latitude,
                fromLon = it.longitude,
                toLat = calibratedTarget.latitude,
                toLon = calibratedTarget.longitude,
                fromElevationM = it.altitudeM,
                toElevationM = cell.rawElevationM
            )
        }
        _uiState.update {
            it.copy(
                selectedCell = cell,
                selectedTargetLabel = "سلول شبکه ماهالانوبیس",
                rawTargetPointZero = rawTarget,
                targetPointZero = calibratedTarget,
                liveGuidanceOffset = liveOffset
            )
        }
        recalculateCalculatorOffset()
    }

    fun selectDetectedTarget(point: DetectedRadarPoint, label: String) {
        val rawTarget = GeoPoint(point.latitude, point.longitude)
        val st = _uiState.value
        val calibratedTarget = applyGcpIfEnabled(rawTarget, st)
        val liveFix = st.liveGps
        val liveOffset = liveFix?.let {
            RadarMathEngine.computeGeodeticOffset(
                fromLat = it.latitude,
                fromLon = it.longitude,
                toLat = calibratedTarget.latitude,
                toLon = calibratedTarget.longitude,
                fromElevationM = it.altitudeM
            )
        }
        _uiState.update {
            it.copy(
                selectedTargetLabel = label,
                rawTargetPointZero = rawTarget,
                targetPointZero = calibratedTarget,
                liveGuidanceOffset = liveOffset
            )
        }
        recalculateCalculatorOffset()
    }

    fun toggleAudioGuidance() {
        val next = !_uiState.value.audioGuidanceEnabled
        _uiState.update { it.copy(audioGuidanceEnabled = next) }
        if (next) {
            lastSpokenAtMs = 0L
            lastSpokenDistM = Double.NaN
            lastSpokenTurnDeg = Double.NaN
            wasLockedAnnounced = false
            startAudioLoop()
        } else {
            audioLoopJob?.cancel()
            audioLoopJob = null
            voiceNav.stop()
        }
    }

    fun toggleTtsGuidance() {
        val next = !_uiState.value.ttsGuidanceEnabled
        _uiState.update { it.copy(ttsGuidanceEnabled = next) }
        if (!next) {
            voiceNav.stop()
        } else if (_uiState.value.audioGuidanceEnabled) {
            lastSpokenAtMs = 0L
            speakCurrentNavigationCueNow()
        }
    }

    fun speakCurrentNavigationCueNow() {
        val st = _uiState.value
        val offset = st.liveGuidanceOffset
        if (offset == null || st.targetPointZero == null) {
            _uiState.update {
                it.copy(
                    lastSpokenCue = if (st.targetPointZero == null) {
                        "ابتدا یک هدف روی نقشه یا نتایج اسکن انتخاب کنید."
                    } else {
                        "در انتظار دریافت موقعیت GPS گوشی برای اعلام مسیر..."
                    }
                )
            }
            return
        }
        val relTurn = VoiceNavigationFormatter.computeRelativeTurnDeg(
            targetBearingDeg = offset.forwardAzimuthDeg,
            deviceYawDeg = st.quaternion.yawDeg
        )
        val cue = voiceNav.speakNavigationCue(
            distanceMeters = offset.distanceMeters,
            targetBearingDeg = offset.forwardAzimuthDeg,
            deviceYawDeg = st.quaternion.yawDeg,
            forceImmediate = true
        )
        lastSpokenAtMs = System.currentTimeMillis()
        lastSpokenDistM = offset.distanceMeters
        lastSpokenTurnDeg = relTurn
        wasLockedAnnounced = offset.distanceMeters <= 3.0
        _uiState.update { it.copy(lastSpokenCue = cue) }
    }

    private fun startAudioLoop() {
        audioLoopJob?.cancel()
        audioLoopJob = viewModelScope.launch {
            while (isActive && _uiState.value.audioGuidanceEnabled) {
                val st = _uiState.value
                val offset = st.liveGuidanceOffset
                val dist = offset?.distanceMeters
                if (offset != null && dist != null) {
                    val locked = dist <= 3.0
                    telemetry.playProximityBeep(locked)

                    if (st.ttsGuidanceEnabled) {
                        val relTurn = VoiceNavigationFormatter.computeRelativeTurnDeg(
                            targetBearingDeg = offset.forwardAzimuthDeg,
                            deviceYawDeg = st.quaternion.yawDeg
                        )
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastSpokenAtMs
                        val cooldownMs = if (dist <= 15.0) 3500L else 5500L
                        val distChanged = lastSpokenDistM.isNaN() || abs(dist - lastSpokenDistM) >= 5.0
                        val turnChanged = lastSpokenTurnDeg.isNaN() ||
                            abs(((relTurn - lastSpokenTurnDeg + 540.0) % 360.0) - 180.0) >= 30.0
                        val lockTransition = locked && !wasLockedAnnounced
                        val thresholdCrossed = !lastSpokenDistM.isNaN() &&
                            ((lastSpokenDistM > 25.0 && dist <= 25.0) ||
                                (lastSpokenDistM > 10.0 && dist <= 10.0))

                        val shouldSpeak = lastSpokenAtMs == 0L ||
                            lockTransition ||
                            elapsed >= cooldownMs ||
                            (elapsed >= 1800L && (distChanged || turnChanged || thresholdCrossed))

                        if (shouldSpeak) {
                            val cue = voiceNav.speakNavigationCue(
                                distanceMeters = dist,
                                targetBearingDeg = offset.forwardAzimuthDeg,
                                deviceYawDeg = st.quaternion.yawDeg,
                                forceImmediate = lockTransition
                            )
                            lastSpokenAtMs = now
                            lastSpokenDistM = dist
                            lastSpokenTurnDeg = relTurn
                            wasLockedAnnounced = locked
                            _uiState.update { it.copy(lastSpokenCue = cue) }
                        }
                    }

                    val intervalMs = when {
                        dist <= 3.0 -> 300L
                        dist <= 15.0 -> 550L
                        dist <= 50.0 -> 1000L
                        else -> 1650L
                    }
                    delay(intervalMs)
                } else {
                    delay(1000L)
                }
            }
        }
    }

    fun onOffsetModeChanged(mode: OffsetMode) {
        _uiState.update { it.copy(offsetMode = mode) }
        recalculateCalculatorOffset()
    }

    fun onGroundLatChanged(value: String) {
        _uiState.update { it.copy(groundLatInput = value) }
        recalculateCalculatorOffset()
    }

    fun onGroundLonChanged(value: String) {
        _uiState.update { it.copy(groundLonInput = value) }
        recalculateCalculatorOffset()
    }

    fun onDeltaEastChanged(value: String) {
        _uiState.update { it.copy(deltaEastInput = value) }
        recalculateCalculatorOffset()
    }

    fun onDeltaNorthChanged(value: String) {
        _uiState.update { it.copy(deltaNorthInput = value) }
        recalculateCalculatorOffset()
    }

    fun recalculateCalculatorOffset() {
        val state = _uiState.value
        val gLat = state.groundLatInput.trim().toDoubleOrNull()
        val gLon = state.groundLonInput.trim().toDoubleOrNull()
        if (gLat == null || gLon == null) return

        val refTarget = state.targetPointZero ?: run {
            val cLat = state.latInput.trim().toDoubleOrNull()
            val cLon = state.lonInput.trim().toDoubleOrNull()
            if (cLat != null && cLon != null) GeoPoint(cLat, cLon) else return
        }

        if (state.offsetMode == OffsetMode.FORWARD_FROM_CENTER) {
            val offset = RadarMathEngine.computeGeodeticOffset(
                fromLat = gLat,
                fromLon = gLon,
                toLat = refTarget.latitude,
                toLon = refTarget.longitude
            )
            _uiState.update {
                it.copy(
                    calculatorOffsetResult = offset,
                    computedReverseTarget = refTarget
                )
            }
        } else {
            val dE = state.deltaEastInput.trim().toDoubleOrNull() ?: 0.0
            val dN = state.deltaNorthInput.trim().toDoubleOrNull() ?: 0.0
            val computed = RadarMathEngine.offsetCoordinate(gLat, gLon, dE, dN)
            val offset = RadarMathEngine.computeGeodeticOffset(
                fromLat = gLat,
                fromLon = gLon,
                toLat = computed.latitude,
                toLon = computed.longitude
            )
            _uiState.update {
                it.copy(
                    computedReverseTarget = computed,
                    calculatorOffsetResult = offset,
                    rawTargetPointZero = computed,
                    targetPointZero = computed
                )
            }
        }
    }

    fun setShowMethodologyDialog(show: Boolean) {
        _uiState.update { it.copy(showMethodologyDialog = show) }
    }

    suspend fun fetchMapTile(url: String): Bitmap? = repository.fetchMapTile(url)

    fun selectCustomTarget(point: GeoPoint) {
        val st = _uiState.value
        val calibrated = applyGcpIfEnabled(point, st)
        val liveOffset = st.liveGps?.let {
            RadarMathEngine.computeGeodeticOffset(
                fromLat = it.latitude,
                fromLon = it.longitude,
                toLat = calibrated.latitude,
                toLon = calibrated.longitude,
                fromElevationM = it.altitudeM
            )
        }
        _uiState.update {
            it.copy(
                selectedTargetLabel = "نقطه انتخاب‌شده روی نقشه زنده",
                rawTargetPointZero = point,
                targetPointZero = calibrated,
                liveGuidanceOffset = liveOffset
            )
        }
        recalculateCalculatorOffset()
    }

    fun captureTargetSatelliteImagery() {
        val st = _uiState.value
        val pt = st.targetPointZero ?: run {
            val lat = st.latInput.trim().toDoubleOrNull()
            val lon = st.lonInput.trim().toDoubleOrNull()
            if (lat != null && lon != null) GeoPoint(lat, lon) else null
        } ?: return

        _uiState.update { it.copy(isLoadingTargetImagery = true, targetImageryError = null) }
        viewModelScope.launch {
            try {
                val bundle = repository.fetchTargetSatelliteImagery(pt.latitude, pt.longitude, 180.0)
                _uiState.update {
                    it.copy(
                        targetImagery = bundle,
                        isLoadingTargetImagery = false,
                        targetImageryError = if (bundle.trueColorRgb == null && bundle.mineralSwir == null && bundle.sarBackscatter == null) {
                            "تصویری از سرور ماهواره برای این کادر برنگشت؛ اتصال اینترنت را بررسی کنید."
                        } else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingTargetImagery = false,
                        targetImageryError = "خطا در دریافت تصویر ماهواره‌ای: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun updateAiConfig(config: AiEndpointConfig) {
        aiRepository.saveConfig(config)
        _uiState.update { it.copy(aiConfig = config, aiError = null) }
    }

    fun updateAiTrainingNotes(notes: String) {
        aiRepository.saveTrainingNotes(notes)
        _uiState.update { it.copy(aiTrainingNotes = notes.trim()) }
    }

    fun recordAiExperience(title: String, category: String, outcome: String) {
        val cleanTitle = title.trim()
        val cleanOutcome = outcome.trim()
        if (cleanTitle.isBlank() && cleanOutcome.isBlank()) return
        val st = _uiState.value
        val entry = AiExperienceEntry(
            id = System.currentTimeMillis(),
            timestampMs = System.currentTimeMillis(),
            title = cleanTitle.ifBlank { category },
            category = category.trim().ifBlank { "تجربه میدانی" },
            learnedRuleOrOutcome = cleanOutcome.ifBlank { cleanTitle },
            latitude = st.liveGps?.latitude ?: st.targetPointZero?.latitude,
            longitude = st.liveGps?.longitude ?: st.targetPointZero?.longitude,
            magDeltaUt = st.magnetometerReading?.let { st.magDeltaUt },
            stftPeakHz = st.magStftResult?.dominantFreqHz,
            apparentDepthM = st.magStftResult?.apparentMaxDepthM,
            mahalanobisDm = (st.scanState as? ScanState.Success)?.result?.fusionResult?.peakMahalanobisDm
        )
        val updated = aiRepository.addExperience(entry)
        _uiState.update { it.copy(aiExperiences = updated) }
    }

    fun deleteAiExperience(id: Long) {
        val updated = aiRepository.deleteExperience(id)
        _uiState.update { it.copy(aiExperiences = updated) }
    }

    fun attachAiImage(bitmap: Bitmap?) {
        _uiState.update { it.copy(aiAttachedImage = bitmap) }
    }

    fun onAiPromptChanged(value: String) {
        _uiState.update { it.copy(aiPromptInput = value) }
    }

    fun toggleAiVoiceReply() {
        _uiState.update { it.copy(aiVoiceReplyEnabled = !it.aiVoiceReplyEnabled) }
    }

    fun stageImageAndPromptForAi(bitmap: Bitmap, suggestedPrompt: String) {
        _uiState.update {
            it.copy(
                aiAttachedImage = bitmap,
                aiPromptInput = suggestedPrompt
            )
        }
    }

    fun sendAiMessage(overrideQuery: String? = null) {
        val st = _uiState.value
        val query = (overrideQuery ?: st.aiPromptInput).trim()
        val img = st.aiAttachedImage
        if (query.isBlank() && img == null) return

        val finalQuery = if (query.isBlank()) {
            "این تصویر میدانی/ماهواره‌ای را از نظر آثار باستان‌شناسی، جنس خاک و احتمال سازه بررسی کن."
        } else query

        val userMsg = AiChatMessage(
            id = System.currentTimeMillis(),
            isUser = true,
            text = finalQuery,
            attachedBitmap = img
        )
        val updatedHistory = st.aiMessages + userMsg
        _uiState.update {
            it.copy(
                aiMessages = updatedHistory,
                aiPromptInput = "",
                aiAttachedImage = null,
                isAiLoading = true,
                aiError = null
            )
        }

        val telemetryContext = buildCurrentFieldTelemetrySummary(st)

        viewModelScope.launch {
            val result = aiRepository.askFieldAssistant(
                config = st.aiConfig,
                history = st.aiMessages,
                userQuery = finalQuery,
                attachedImage = img,
                fieldTelemetrySummary = telemetryContext,
                trainingNotes = st.aiTrainingNotes,
                experiences = st.aiExperiences
            )
            result.fold(
                onSuccess = { (replyText, isOfflineMode) ->
                    val botMsg = AiChatMessage(
                        id = System.currentTimeMillis() + 1L,
                        isUser = false,
                        text = replyText,
                        isOfflineTelemetryMode = isOfflineMode
                    )
                    _uiState.update {
                        it.copy(
                            aiMessages = it.aiMessages + botMsg,
                            isAiLoading = false
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isAiLoading = false,
                            aiError = err.localizedMessage ?: "خطا در ارتباط با سرور مدل"
                        )
                    }
                }
            )
        }
    }

    private fun buildCurrentFieldTelemetrySummary(st: RadarUiState): String = buildString {
        st.liveGps?.let {
            append(String.format(Locale.US, "GPS کاربر: %.6f, %.6f (دقت ±%.1fm)\n", it.latitude, it.longitude, it.accuracyM))
        }
        st.targetPointZero?.let {
            append(String.format(Locale.US, "مختصات نقطه هدف: %.6f, %.6f\n", it.latitude, it.longitude))
        }
        st.liveGuidanceOffset?.let {
            append(String.format(Locale.US, "فاصله تا هدف: %.1f متر | آزیموت: %.1f°\n", it.distanceMeters, it.forwardAzimuthDeg))
        }
        st.magnetometerReading?.let { mag ->
            append(
                String.format(
                    Locale.US,
                    "مگنتومتر زنده: |B|=%.1f µT | ΔB=%+.1f µT (Z=%.1fσ)\n",
                    mag.magnitudeUt,
                    st.magDeltaUt,
                    st.magZScore
                )
            )
        }
        st.magStftResult?.let { stft ->
            if (stft.dominantFreqHz > 0.0) {
                append(
                    String.format(
                        Locale.US,
                        "طیف STFT مگنتومتر: Peak=%.2fHz | Centroid=%.2fHz | ApparentMaxDepth≈%.2fm (%s)\n",
                        stft.dominantFreqHz,
                        stft.spectralCentroidHz,
                        stft.apparentMaxDepthM,
                        stft.anomalyClassLabel
                    )
                )
            }
        }
        st.adaptiveDepthFilter?.let { df ->
            append(
                String.format(
                    Locale.US,
                    "فیلتر تطبیقی عمق [%s]: DeepEnv=%+.2fµT | Clutter=%.2fµT | Footprint=%.2fm | Thresh=%.1fµT | ClutterSuppressed=%s\n",
                    df.filterModeLabel,
                    df.deepEnvelopeUt,
                    df.shallowClutterUt,
                    df.soilFootprintWidthM,
                    df.adaptiveThresholdUt,
                    if (df.surfaceClutterSuppressed) "YES" else "NO"
                )
            )
        }
        st.liveMaterialSignature?.let { ms ->
            if (ms.rankedMatches.isNotEmpty()) {
                val topSummary = ms.rankedMatches.take(3).joinToString(", ") {
                    "${it.displayName}:${it.similarityPercent}%"
                }
                append("امضای طیفی مواد: ${ms.primaryClassLabel} ($topSummary)\n")
            }
        }
        st.lastOpticalMeta?.let { opt ->
            append(
                String.format(
                    Locale.US,
                    "تحلیل نوری دوربین: Sarooj=%.1f%% | Hotspot=%.1f%% | ContrastZ=%.2f | LocalizedAnomaly=%s\n",
                    opt.saroojMatchPct,
                    opt.hotspotCoveragePct,
                    opt.peakSurfaceContrastZ,
                    if (opt.isLocalizedOpticalAnomaly) "YES" else "NO"
                )
            )
        }
        (st.scanState as? ScanState.Success)?.result?.let { r ->
            append(
                String.format(
                    Locale.US,
                    "Peak 5D Mahalanobis Dm: %.2f | Priority Targets: %d | Clean CFAR: %d | Carbonate (>3σ): %d",
                    r.fusionResult.peakMahalanobisDm,
                    r.fusionResult.priorityTargets.size,
                    r.fusionResult.cleanRadarPoints.size,
                    r.fusionResult.carbonatePoints.size
                )
            )
            r.fusionResult.materialSignatures?.let { satSig ->
                append(" | Satellite Dominant Material: ${satSig.dominantMaterialLabel}")
            }
        }
    }.ifBlank { "داده اسکن هنوز ثبت نشده است." }

    fun setAiCompanionSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isAiCompanionSheetOpen = open) }
    }

    fun updateLiveReceiverFrame(bitmap: Bitmap?) {
        _uiState.update { it.copy(liveReceiverFrameBitmap = bitmap) }
    }

    fun setCompanionAttachedImage(bitmap: Bitmap?) {
        _uiState.update { it.copy(companionAttachedImage = bitmap) }
    }

    fun dismissWatchdogBanner() {
        _uiState.update {
            it.copy(
                watchdogAlertTitle = null,
                watchdogAlertMessage = null,
                watchdogCloudVerdict = null
            )
        }
    }

    fun toggleAiWatchdog() {
        val nextEnabled = !_uiState.value.aiWatchdogEnabled
        fieldWatchdog.reset()
        _uiState.update {
            it.copy(
                aiWatchdogEnabled = nextEnabled,
                watchdogAlertTitle = if (nextEnabled) it.watchdogAlertTitle else null,
                watchdogAlertMessage = if (nextEnabled) it.watchdogAlertMessage else null,
                watchdogCloudVerdict = if (nextEnabled) it.watchdogCloudVerdict else null
            )
        }
        voiceNav.speakCustomMessage(
            if (nextEnabled) {
                "ناظر هوشمند با مجوز شما فعال شد؛ اگر از هدف رد شوی یا خطا ببیند فوری خبر می‌دهم."
            } else {
                "ناظر هوشمند خاموش شد؛ تا خودت نپرسی مزاحم نمی‌شوم."
            }
        )
    }

    fun connectQuickKeylessCloudAi() {
        val st = _uiState.value
        if (st.aiConfig.endpointUrl.isNotBlank()) return
        val quick = AiEndpointConfig(
            endpointUrl = "https://text.pollinations.ai/openai",
            modelName = "openai",
            apiKey = "",
            systemPrompt = st.aiConfig.systemPrompt
        )
        updateAiConfig(quick)
    }

    fun askLiveCompanion(userQuery: String) {
        val st = _uiState.value
        val trimmed = userQuery.trim().ifBlank {
            "لطفاً با تصویر و داده‌های سنسور همین لحظه بگو چه می‌بینی، آیا جای مهمی را رد کردم یا خطای طبیعی است؟"
        }
        val activeImg = st.companionAttachedImage ?: st.liveReceiverFrameBitmap
        val telemetryContext = buildCurrentFieldTelemetrySummary(st)

        val userMsg = AiChatMessage(
            id = System.currentTimeMillis(),
            isUser = true,
            text = "[همراه زنده میدان] $trimmed",
            attachedBitmap = activeImg
        )
        _uiState.update {
            it.copy(
                aiMessages = it.aiMessages + userMsg,
                isCompanionLoading = true,
                companionError = null,
                companionAttachedImage = null
            )
        }

        viewModelScope.launch {
            val result = aiRepository.askFieldAssistant(
                config = st.aiConfig,
                history = st.aiMessages,
                userQuery = "$trimmed\n(پاسخ را کوتاه، دقیق، بدون تعارف و کاملاً کاربردی برای کسی که الان در صحرا ایستاده بگو.)",
                attachedImage = activeImg,
                fieldTelemetrySummary = telemetryContext,
                trainingNotes = st.aiTrainingNotes,
                experiences = st.aiExperiences
            )
            result.fold(
                onSuccess = { (replyText, isOfflineMode) ->
                    val botMsg = AiChatMessage(
                        id = System.currentTimeMillis() + 1L,
                        isUser = false,
                        text = replyText,
                        isOfflineTelemetryMode = isOfflineMode
                    )
                    _uiState.update {
                        it.copy(
                            aiMessages = it.aiMessages + botMsg,
                            companionLastReply = replyText,
                            isCompanionLoading = false
                        )
                    }
                    if (_uiState.value.aiVoiceReplyEnabled) {
                        voiceNav.speakCustomMessage(replyText, forceImmediate = true)
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isCompanionLoading = false,
                            companionError = err.localizedMessage ?: "خطا در ارتباط با سرور مدل"
                        )
                    }
                }
            )
        }
    }

    private fun evaluateWatchdogIfPermitted() {
        val st = _uiState.value
        if (!st.aiWatchdogEnabled) return
        val now = System.currentTimeMillis()
        val decision = fieldWatchdog.evaluateStep(
            nowMs = now,
            enabled = true,
            distanceToTargetM = st.liveGuidanceOffset?.distanceMeters,
            isMagCalibrated = st.isMagBaselineCalibrated,
            depthFilter = st.adaptiveDepthFilter,
            materialSig = st.liveMaterialSignature
        ) ?: return

        _uiState.update {
            it.copy(
                watchdogAlertTitle = decision.bannerTitleFa,
                watchdogAlertMessage = decision.bannerDetailFa,
                watchdogCloudVerdict = null
            )
        }
        voiceNav.speakCustomMessage(decision.spokenMessageFa, forceImmediate = true)

        if (decision.shouldConsultCloudVision &&
            st.aiConfig.endpointUrl.isNotBlank() &&
            !isWatchdogCloudConsultRunning &&
            now - lastWatchdogCloudConsultAtMs >= 20_000L
        ) {
            isWatchdogCloudConsultRunning = true
            lastWatchdogCloudConsultAtMs = now
            val snapshot = st.liveReceiverFrameBitmap ?: st.companionAttachedImage
            val telemetryContext = buildCurrentFieldTelemetrySummary(st)
            viewModelScope.launch {
                try {
                    val res = aiRepository.askFieldAssistant(
                        config = st.aiConfig,
                        history = st.aiMessages.takeLast(2),
                        userQuery = decision.cloudVerificationPromptFa,
                        attachedImage = snapshot,
                        fieldTelemetrySummary = telemetryContext,
                        trainingNotes = st.aiTrainingNotes,
                        experiences = st.aiExperiences
                    )
                    res.onSuccess { (verdictText, _) ->
                        val shortVerdict = verdictText.trim()
                        _uiState.update { cur ->
                            cur.copy(
                                watchdogCloudVerdict = shortVerdict,
                                companionLastReply = shortVerdict,
                                aiMessages = cur.aiMessages + AiChatMessage(
                                    id = System.currentTimeMillis(),
                                    isUser = false,
                                    text = "[تصحیح خودکار ناظر]\n$shortVerdict",
                                    isOfflineTelemetryMode = false
                                )
                            )
                        }
                        if (_uiState.value.aiVoiceReplyEnabled && _uiState.value.aiWatchdogEnabled) {
                            voiceNav.speakCustomMessage(shortVerdict, forceImmediate = false)
                        }
                    }
                } finally {
                    isWatchdogCloudConsultRunning = false
                }
            }
        }
    }

    fun onMagSweepSpeedChanged(speedMps: Float) {
        val clamped = speedMps.coerceIn(0.15f, 1.2f)
        _uiState.update { st ->
            val recomputed = if (st.magHistoryUt.size >= 16) {
                FieldReceiverMath.computeMagnetometerStft(
                    samplesUt = st.magHistoryUt,
                    sampleRateHz = st.magSampleRateHz,
                    sweepSpeedMps = clamped.toDouble(),
                    currentDeltaUt = st.magDeltaUt,
                    baselineStdUt = st.magBaselineStdUt
                )
            } else st.magStftResult
            st.copy(magSweepSpeedMps = clamped, magStftResult = recomputed)
        }
    }

    fun setFieldReceiverActive(active: Boolean) {
        if (active) {
            telemetry.startMagnetometer()
            val st = _uiState.value
            if (!st.isMagBaselineCalibrated && !st.isCalibratingMagBaseline) {
                calibrateMagnetometerBaseline()
            }
        } else {
            telemetry.stopMagnetometer()
            magAudioLoopJob?.cancel()
            magAudioLoopJob = null
            if (_uiState.value.magAudioEnabled) {
                _uiState.update { it.copy(magAudioEnabled = false) }
            }
        }
    }

    fun calibrateMagnetometerBaseline() {
        magCalibSamples.clear()
        val current = _uiState.value.magnetometerReading?.magnitudeUt
        if (current != null) {
            magCalibSamples.add(current)
        }
        _uiState.update {
            it.copy(
                isCalibratingMagBaseline = true,
                isMagBaselineCalibrated = false,
                isMagConfirmedAlert = false,
                magConsecutiveAlertCount = 0,
                magCalibrationCount = magCalibSamples.size
            )
        }
    }

    fun onMagSensitivityChanged(value: Float) {
        _uiState.update { it.copy(magSensitivityThresholdUt = value) }
    }

    fun onDepthFilterModeChanged(mode: Int) {
        _uiState.update { st ->
            val coerced = mode.coerceIn(0, 3)
            val base = st.magBaselineMeanUt ?: st.magnetometerReading?.magnitudeUt ?: 45.0
            val recomputedDepth = adaptiveDepthFilter.filterAndIsolateDeepSignal(
                samplesUt = st.magHistoryUt,
                baselineMeanUt = base,
                baselineStdUt = st.magBaselineStdUt,
                stft = st.magStftResult,
                opticalMeta = st.lastOpticalMeta,
                userBaseThresholdUt = st.magSensitivityThresholdUt,
                depthBandMode = coerced,
                sweepSpeedMps = st.magSweepSpeedMps.toDouble()
            )
            val recomputedSig = materialSpectralProcessor.analyzeLiveSignature(
                stft = st.magStftResult,
                depthFilter = recomputedDepth,
                baselineStdUt = st.magBaselineStdUt,
                opticalMeta = st.lastOpticalMeta
            )
            st.copy(
                depthFilterMode = coerced,
                adaptiveDepthFilter = recomputedDepth,
                liveMaterialSignature = recomputedSig
            )
        }
    }

    fun toggleAutoDetectionSound() {
        _uiState.update { it.copy(autoDetectionSoundEnabled = !it.autoDetectionSoundEnabled) }
    }

    fun onOpticalFrameAnalyzed(meta: ThermalStylePixelResult?, cameraActive: Boolean) {
        var triggeredLevel = 0
        _uiState.update { st ->
            val nextConsec = if (cameraActive && meta != null && meta.isLocalizedOpticalAnomaly) {
                (st.opticalConsecutiveAlertCount + 1).coerceAtMost(20)
            } else 0
            val opticalConfirmed = cameraActive && nextConsec >= 4
            val verdict = FieldReceiverMath.evaluateDetectionAlert(
                isMagBaselineCalibrated = st.isMagBaselineCalibrated,
                isMagConfirmed = st.isMagConfirmedAlert,
                isOpticalConfirmed = opticalConfirmed,
                isCameraActive = cameraActive,
                magDeltaUt = st.magDeltaUt,
                opticalMeta = meta,
                indoorWarning = st.indoorInterferenceWarning
            )
            triggeredLevel = verdict.level
            st.copy(
                isReceiverCameraActive = cameraActive,
                lastOpticalMeta = meta,
                opticalConsecutiveAlertCount = nextConsec,
                isOpticalConfirmedAlert = opticalConfirmed,
                confirmedDetectionLevel = verdict.level,
                confirmedAlertTitle = verdict.title,
                confirmedAlertDetail = verdict.detail
            )
        }
        maybeTriggerDetectionAlertTone(triggeredLevel)
    }

    private fun maybeTriggerDetectionAlertTone(level: Int) {
        if (level == 0) {
            lastAlertLevelTriggered = 0
            return
        }
        if (!_uiState.value.autoDetectionSoundEnabled) return
        val now = System.currentTimeMillis()
        val upgraded = level > lastAlertLevelTriggered
        val cooldownElapsed = now - lastConfirmedAlertSoundAtMs >= 2600L
        if (upgraded || cooldownElapsed) {
            lastConfirmedAlertSoundAtMs = now
            lastAlertLevelTriggered = level
            telemetry.playConfirmedDetectionAlert(isDualSensor = level == 3)
        }
    }

    fun manualTrimRam() {
        performRamTrim(isAutomatic = false)
    }

    private fun refreshAndAutoTrimMemory() {
        val rt = Runtime.getRuntime()
        val usedBytes = rt.totalMemory() - rt.freeMemory()
        val maxBytes = rt.maxMemory()
        val (pct, usedMb, maxMb) = FieldReceiverMath.computeRamUsageMetrics(usedBytes, maxBytes)
        _uiState.update {
            it.copy(
                ramUsagePercent = pct,
                ramUsedMb = usedMb,
                ramMaxMb = maxMb
            )
        }
        if (FieldReceiverMath.shouldAutoCleanRam(pct, 90)) {
            performRamTrim(isAutomatic = true, triggerPct = pct)
        }
    }

    private fun performRamTrim(isAutomatic: Boolean, triggerPct: Int? = null) {
        val rtBefore = Runtime.getRuntime()
        val usedBeforeBytes = rtBefore.totalMemory() - rtBefore.freeMemory()
        val evictedTiles = repository.trimMemoryCaches()
        _uiState.update { st ->
            val trimmedMessages = if (st.aiMessages.size > 4) {
                st.aiMessages.mapIndexed { idx, msg ->
                    if (idx < st.aiMessages.size - 2 && msg.attachedBitmap != null) {
                        msg.copy(attachedBitmap = null)
                    } else msg
                }
            } else st.aiMessages
            val trimmedPins = if (st.fieldReceiverPins.size > 5) {
                st.fieldReceiverPins.mapIndexed { idx, pin ->
                    if (idx >= 5) pin.copy(snapshot = null) else pin
                }
            } else st.fieldReceiverPins
            st.copy(
                targetImagery = if (isAutomatic) null else st.targetImagery,
                magWaterfallSpectrogram = st.magWaterfallSpectrogram.takeLast(6),
                aiMessages = trimmedMessages,
                fieldReceiverPins = trimmedPins,
                liveReceiverFrameBitmap = if (isAutomatic) null else st.liveReceiverFrameBitmap,
                companionAttachedImage = if (isAutomatic) null else st.companionAttachedImage
            )
        }
        System.gc()
        val rtAfter = Runtime.getRuntime()
        val usedAfterBytes = rtAfter.totalMemory() - rtAfter.freeMemory()
        val (pctAfter, usedMbAfter, maxMbAfter) = FieldReceiverMath.computeRamUsageMetrics(usedAfterBytes, rtAfter.maxMemory())
        val freedMb = (((usedBeforeBytes - usedAfterBytes).coerceAtLeast(0L)) / (1024L * 1024L)).toInt()
        val label = if (isAutomatic) {
            "پاکسازی خودکار رم در ${triggerPct ?: 90}٪ انجام شد ($evictedTiles کاشی + ${freedMb}MB آزاد شد)"
        } else {
            "پاکسازی دستی رم انجام شد ($evictedTiles کاشی + ${freedMb}MB آزاد شد)"
        }
        _uiState.update {
            it.copy(
                ramUsagePercent = pctAfter,
                ramUsedMb = usedMbAfter,
                ramMaxMb = maxMbAfter,
                lastAutoRamCleanAtMs = System.currentTimeMillis(),
                lastAutoRamCleanStatus = label
            )
        }
    }

    fun toggleMagAudio() {
        val next = !_uiState.value.magAudioEnabled
        _uiState.update { it.copy(magAudioEnabled = next) }
        if (next) {
            magAudioLoopJob?.cancel()
            magAudioLoopJob = viewModelScope.launch {
                while (isActive && _uiState.value.magAudioEnabled) {
                    val st = _uiState.value
                    val absSig = abs(st.adaptiveDepthFilter?.activeSignalUt ?: st.magDeltaUt)
                    val adapted = st.adaptiveDepthFilter?.adaptiveThresholdUt ?: st.magSensitivityThresholdUt.toDouble()
                    val thresh = if (st.magEffectiveFloorUt > st.magSensitivityThresholdUt + 1.0) {
                        maxOf(adapted, st.magEffectiveFloorUt)
                    } else adapted
                    if (st.magnetometerReading != null && st.isMagBaselineCalibrated && st.isMagConfirmedAlert && absSig >= thresh) {
                        val strong = absSig >= thresh * 2.2
                        telemetry.playMagneticGeigerTone(strong)
                        val delayMs = when {
                            absSig >= thresh * 3.0 -> 220L
                            absSig >= thresh * 2.0 -> 380L
                            else -> 650L
                        }
                        delay(delayMs)
                    } else {
                        delay(250L)
                    }
                }
            }
        } else {
            magAudioLoopJob?.cancel()
            magAudioLoopJob = null
        }
    }

    fun cycleOpticalOverlayMode() {
        _uiState.update {
            it.copy(receiverOpticalOverlayMode = (it.receiverOpticalOverlayMode + 1) % 5)
        }
    }

    fun selectReceiverCaptureAngle(index: Int) {
        _uiState.update { it.copy(receiverCaptureAngleIndex = index.coerceIn(0, 4)) }
    }

    fun recordFieldAnomalyPin(bitmap: Bitmap?, opticalStats: OpticalGridStats?) {
        val st = _uiState.value
        val mag = st.magnetometerReading
        val angleLabels = listOf("مرکز (بالا)", "شمال", "شرق", "جنوب", "غرب")
        val angleTag = angleLabels.getOrElse(st.receiverCaptureAngleIndex) { "مرکز" }
        val pin = FieldAnomalyPin(
            id = System.currentTimeMillis(),
            timestampMs = System.currentTimeMillis(),
            angleTag = angleTag,
            bxUt = mag?.bxUt ?: 0.0,
            byUt = mag?.byUt ?: 0.0,
            bzUt = mag?.bzUt ?: 0.0,
            magnitudeUt = mag?.magnitudeUt ?: 0.0,
            deltaUt = st.magDeltaUt,
            zScore = st.magZScore,
            yawDeg = st.quaternion.yawDeg,
            latitude = st.liveGps?.latitude,
            longitude = st.liveGps?.longitude,
            peakOpticalChroma = opticalStats?.peakChromaIndex,
            edgeDensity = opticalStats?.edgeDensity,
            stftPeakHz = st.magStftResult?.dominantFreqHz,
            apparentDepthM = st.magStftResult?.apparentMaxDepthM,
            snapshot = bitmap
        )
        _uiState.update {
            it.copy(
                fieldReceiverPins = (listOf(pin) + it.fieldReceiverPins).take(12),
                receiverCaptureAngleIndex = (st.receiverCaptureAngleIndex + 1) % 5
            )
        }
    }

    fun clearFieldAnomalyPins() {
        _uiState.update { it.copy(fieldReceiverPins = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        audioLoopJob?.cancel()
        magAudioLoopJob?.cancel()
        voiceNav.release()
        telemetry.release()
    }
}
