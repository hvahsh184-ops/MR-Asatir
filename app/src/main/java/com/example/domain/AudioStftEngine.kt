package com.example.domain

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class PassiveMaterialId(
    val id: String,
    val titleFa: String,
    val subtitleFa: String,
    val bandMinHz: Float,
    val bandMaxHz: Float,
    val accentColorHex: Long,
    val isFerrousOrClutter: Boolean = false
) {
    VOID_CAVITY(
        id = "void_cavity",
        titleFa = "حفره / اتاقک / ساروج",
        subtitleFa = "تشدید آکوستیک-مکانیکی عمیق",
        bandMinHz = 20f,
        bandMaxHz = 180f,
        accentColorHex = 0xFF00E5A3
    ),
    GOLD_DIAMAG(
        id = "gold_diamag",
        titleFa = "طلا / رساناهای دیامغناطیس",
        subtitleFa = "باند القای فرکانس پایین پایدار",
        bandMinHz = 180f,
        bandMaxHz = 480f,
        accentColorHex = 0xFFFFD54F
    ),
    SILVER_HIGH_COND(
        id = "silver_cond",
        titleFa = "نقره / فلز با رسانایی بالا",
        subtitleFa = "پاسخ هارمونیک میانه-پایین",
        bandMinHz = 480f,
        bandMaxHz = 1350f,
        accentColorHex = 0xFF38BDF8
    ),
    COPPER_BRONZE(
        id = "copper_bronze",
        titleFa = "مس / برنز و هاله اکسید",
        subtitleFa = "تشدید میانی و اکسیداسیون خاک",
        bandMinHz = 1350f,
        bandMaxHz = 4200f,
        accentColorHex = 0xFFFFAB91
    ),
    FERROUS_IRON(
        id = "ferrous_iron",
        titleFa = "آهن / فولاد فرومغناطیس",
        subtitleFa = "اسپایک تیز سطحی (قابل فیلتر/حذف)",
        bandMinHz = 4200f,
        bandMaxHz = 14500f,
        accentColorHex = 0xFFFF4D6A,
        isFerrousOrClutter = true
    ),
    VLF_TELLURIC(
        id = "vlf_telluric",
        titleFa = "رگه VLF / کوارتز (باند 192kHz)",
        subtitleFa = "فراصوت و سیگنال تلوریک تا ۹۶ کیلوهرتز",
        bandMinHz = 14500f,
        bandMaxHz = 96000f,
        accentColorHex = 0xFFB388FF
    )
}

data class MaterialBandReading(
    val material: PassiveMaterialId,
    val enabled: Boolean,
    val matchScorePct: Int,
    val peakBandHz: Float,
    val bandEnergyDb: Float
)

data class PassiveDepthTelemetry(
    val hasValidLock: Boolean,
    val estimatedDepthMeters: Float,
    val depthErrorMarginMeters: Float,
    val depthZoneFa: String,
    val inferredGeometryFa: String,
    val qFactor: Float,
    val elfEnergyPct: Float,
    val voidEnergyPct: Float,
    val telluricEnergyPct: Float,
    val highVlfEnergyPct: Float
)

data class AudioStftResult(
    val sampleRateHz: Int,
    val nyquistHz: Int,
    val dominantFrequencyHz: Float,
    val peakMagnitude: Float,
    val snrDb: Float,
    val rmsDbfs: Float,
    val anomalyScore: Float,
    val spectrumVicoDb: List<Double>,
    val noiseFloorVicoDb: List<Double>,
    val spectrumAxisLabelsHz: List<Float>,
    val waterfallBandRow: List<Float>,
    val waveformPreview: List<Float>,
    val materialReadings: List<MaterialBandReading>,
    val topMatchedMaterial: PassiveMaterialId?,
    val depthTelemetry: PassiveDepthTelemetry,
    val analysisVerdict: String
)

/**
 * High-performance, pre-allocated Radix-2 Cooley-Tukey STFT Engine tuned for 192kHz
 * passive headphone/antenna capture. Runs off the main thread with zero per-frame
 * large array allocations.
 */
class AudioStftEngine(
    val fftSize: Int = 4096,
    initialSampleRateHz: Int = 192000
) {
    private var sampleRateHz: Int = initialSampleRateHz.coerceAtLeast(8000)

    private val hannWindow = DoubleArray(fftSize) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))
    }

    // Preallocated scratch buffers (used exclusively by the single-threaded DSP worker)
    private val real = DoubleArray(fftSize)
    private val imag = DoubleArray(fftSize)
    private val halfBins = fftSize / 2
    private val magSpectrum = DoubleArray(halfBins)
    private val noiseFloorBins = DoubleArray(halfBins) { 0.0 }
    private val calibAccumBins = DoubleArray(halfBins) { 0.0 }
    private var calibFrameCount = 0
    private var isCalibrated = false

    private val vicoBinCount = 48
    private val waterfallBinCount = 32

    fun updateSampleRate(rateHz: Int) {
        if (rateHz > 0 && rateHz != sampleRateHz) {
            sampleRateHz = rateHz
            resetCalibration()
        }
    }

    fun resetCalibration() {
        calibAccumBins.fill(0.0)
        noiseFloorBins.fill(0.0)
        calibFrameCount = 0
        isCalibrated = false
    }

    fun accumulateCalibrationFrame(pcmBuffer: ShortArray, offset: Int, length: Int): Boolean {
        if (length <= 0) return false
        computeRawMagnitudeSpectrum(pcmBuffer, offset, length)
        for (i in 1 until halfBins) {
            calibAccumBins[i] += magSpectrum[i]
        }
        calibFrameCount++
        return true
    }

    fun finalizeCalibration(): Boolean {
        if (calibFrameCount <= 0) return false
        for (i in 1 until halfBins) {
            noiseFloorBins[i] = calibAccumBins[i] / calibFrameCount.toDouble()
        }
        isCalibrated = true
        return true
    }

    fun computeStft(
        pcmBuffer: ShortArray,
        offset: Int,
        length: Int,
        enabledMaterials: Set<PassiveMaterialId>
    ): AudioStftResult {
        val nyquist = sampleRateHz / 2
        if (length <= 0) {
            return emptyResult(nyquist)
        }

        val rmsDbfs = computeRawMagnitudeSpectrum(pcmBuffer, offset, length)
        val waveformPreview = buildWaveformPreview(pcmBuffer, offset, length)

        val binWidthHz = sampleRateHz.toDouble() / fftSize.toDouble()
        val effectiveFloorFactor = if (isCalibrated) 1.15 else 0.0

        // Calculate net (floor-subtracted) spectrum and find peak across enabled materials
        var globalMaxMag = 0.0
        var enabledMaxMag = 0.0
        var enabledPeakBin = 1
        var sumNetMag = 0.0
        var activeBinCount = 0

        var elfEnergy = 0.0
        var voidEnergy = 0.0
        var telluricEnergy = 0.0
        var highVlfEnergy = 0.0

        val materialEnergies = DoubleArray(PassiveMaterialId.entries.size)
        val materialMaxMags = DoubleArray(PassiveMaterialId.entries.size)
        val materialPeakBins = IntArray(PassiveMaterialId.entries.size) { 1 }

        for (k in 1 until halfBins) {
            val freq = k * binWidthHz
            val rawMag = magSpectrum[k]
            val floor = if (isCalibrated) noiseFloorBins[k] * effectiveFloorFactor else 0.0
            val netMag = max(0.0, rawMag - floor)

            if (rawMag > globalMaxMag) globalMaxMag = rawMag
            val energy = netMag * netMag

            when {
                freq < 50.0 -> elfEnergy += energy
                freq < 500.0 -> voidEnergy += energy
                freq < 20000.0 -> telluricEnergy += energy
                else -> highVlfEnergy += energy
            }

            // Assign to material band
            var binEnabled = false
            PassiveMaterialId.entries.forEachIndexed { idx, mat ->
                if (freq >= mat.bandMinHz && freq < mat.bandMaxHz) {
                    materialEnergies[idx] += energy
                    if (netMag > materialMaxMags[idx]) {
                        materialMaxMags[idx] = netMag
                        materialPeakBins[idx] = k
                    }
                    if (mat in enabledMaterials) {
                        binEnabled = true
                    }
                }
            }

            if (binEnabled || enabledMaterials.isEmpty()) {
                sumNetMag += netMag
                activeBinCount++
                if (netMag > enabledMaxMag) {
                    enabledMaxMag = netMag
                    enabledPeakBin = k
                }
            }
        }

        val dominantFreq = (enabledPeakBin * binWidthHz).toFloat()
        val meanMag = if (activeBinCount > 0) (sumNetMag / activeBinCount).coerceAtLeast(1e-7) else 1e-7
        val snrDb = if (enabledMaxMag > 1e-7) {
            (20.0 * log10(enabledMaxMag / meanMag)).toFloat().coerceIn(0f, 72f)
        } else {
            0f
        }

        // Q-Factor estimation (-3dB half-power bandwidth around dominant bin)
        val halfPowerThreshold = enabledMaxMag * 0.7071
        var leftBin = enabledPeakBin
        while (leftBin > 1 && max(0.0, magSpectrum[leftBin] - noiseFloorBins[leftBin] * effectiveFloorFactor) >= halfPowerThreshold) {
            leftBin--
        }
        var rightBin = enabledPeakBin
        while (rightBin < halfBins - 1 && max(0.0, magSpectrum[rightBin] - noiseFloorBins[rightBin] * effectiveFloorFactor) >= halfPowerThreshold) {
            rightBin++
        }
        val bwHz = max(binWidthHz, (rightBin - leftBin).coerceAtLeast(1) * binWidthHz)
        val qFactor = (dominantFreq / bwHz).toFloat().coerceIn(0.5f, 85f)

        val totalEnergy = (elfEnergy + voidEnergy + telluricEnergy + highVlfEnergy).coerceAtLeast(1e-12)
        val elfPct = ((elfEnergy / totalEnergy) * 100.0).toFloat()
        val voidPct = ((voidEnergy / totalEnergy) * 100.0).toFloat()
        val telluricPct = ((telluricEnergy / totalEnergy) * 100.0).toFloat()
        val highVlfPct = ((highVlfEnergy / totalEnergy) * 100.0).toFloat()

        // Material scores
        val readings = PassiveMaterialId.entries.mapIndexed { idx, mat ->
            val enabled = mat in enabledMaterials
            val matEnergy = materialEnergies[idx]
            val matPeak = materialMaxMags[idx]
            val matFreq = (materialPeakBins[idx] * binWidthHz).toFloat()
            val bandRatio = (matEnergy / totalEnergy).coerceIn(0.0, 1.0)
            val bandDb = (20.0 * log10(matPeak.coerceAtLeast(1e-5)) + 100.0).toFloat().coerceIn(0f, 100f)

            val rawScore = ((bandRatio * 68.0) + (min(1.0, matPeak * 9.0) * 32.0)) * if (enabled) 1.0 else 0.35
            MaterialBandReading(
                material = mat,
                enabled = enabled,
                matchScorePct = rawScore.toInt().coerceIn(0, 100),
                peakBandHz = matFreq,
                bandEnergyDb = bandDb
            )
        }

        val topActiveReading = readings
            .filter { it.enabled && it.matchScorePct >= 18 }
            .maxByOrNull { it.matchScorePct }

        val anomalyScore = min(
            100f,
            (snrDb * 2.15f + (enabledMaxMag * 280.0).toFloat()).coerceIn(0f, 100f)
        )

        val hasValidLock = anomalyScore >= 38f && topActiveReading != null

        // Target Depth Telemetry using frequency-dependent skin-depth & LF/HF attenuation ratio
        val depthTelemetry = computeDepthTelemetry(
            hasValidLock = hasValidLock,
            dominantFreqHz = dominantFreq,
            snrDb = snrDb,
            qFactor = qFactor,
            elfPct = elfPct,
            voidPct = voidPct,
            telluricPct = telluricPct,
            highVlfPct = highVlfPct,
            topMaterial = topActiveReading?.material
        )

        // Build 48 log-spaced bins for Vico chart (live spectrum + calibrated noise floor)
        val minPlotHz = 20.0
        val maxPlotHz = nyquist.toDouble().coerceAtLeast(1000.0)
        val logMin = ln(minPlotHz)
        val logSpan = ln(maxPlotHz) - logMin

        val spectrumVicoDb = ArrayList<Double>(vicoBinCount)
        val noiseVicoDb = ArrayList<Double>(vicoBinCount)
        val axisLabelsHz = ArrayList<Float>(vicoBinCount)

        for (b in 0 until vicoBinCount) {
            val fStart = kotlin.math.exp(logMin + (b.toDouble() / vicoBinCount) * logSpan)
            val fEnd = kotlin.math.exp(logMin + ((b + 1).toDouble() / vicoBinCount) * logSpan)
            axisLabelsHz.add(((fStart + fEnd) * 0.5).toFloat())

            val binStart = (fStart / binWidthHz).toInt().coerceIn(1, halfBins - 1)
            val binEnd = (fEnd / binWidthHz).toInt().coerceIn(binStart, halfBins - 1)

            var maxBandNet = 0.0
            var maxBandFloor = 0.0
            for (k in binStart..binEnd) {
                val freq = k * binWidthHz
                val isMatActive = enabledMaterials.isEmpty() || PassiveMaterialId.entries.any {
                    it in enabledMaterials && freq >= it.bandMinHz && freq < it.bandMaxHz
                }
                val floor = if (isCalibrated) noiseFloorBins[k] else (meanMag * 1.1)
                val raw = magSpectrum[k]
                val net = if (isMatActive) max(0.0, raw - floor * 0.65) else max(0.0, raw - floor) * 0.25
                if (net > maxBandNet) maxBandNet = net
                if (floor > maxBandFloor) maxBandFloor = floor
            }

            // Convert to positive dB above -96 dBFS floor for crisp Vico positive plotting
            val netDb = (20.0 * log10(maxBandNet.coerceAtLeast(1e-5)) + 96.0).coerceIn(0.0, 96.0)
            val floorDb = (20.0 * log10(maxBandFloor.coerceAtLeast(1e-5)) + 96.0).coerceIn(0.0, 96.0)
            spectrumVicoDb.add(netDb)
            noiseVicoDb.add(floorDb)
        }

        // Build 32 compressed normalized waterfall bins
        val waterfallRow = ArrayList<Float>(waterfallBinCount)
        for (b in 0 until waterfallBinCount) {
            val vicoIdx = (b * vicoBinCount) / waterfallBinCount
            val dbVal = spectrumVicoDb.getOrElse(vicoIdx) { 0.0 }
            waterfallRow.add(((dbVal - 14.0) / 72.0).toFloat().coerceIn(0f, 1f))
        }

        val verdict = when {
            topActiveReading != null && anomalyScore >= 72f -> String.format(
                Locale.US,
                "قفل ناهنجاری قوی: %s در %.0f Hz (SNR %.1f dB | عمق تخمینی %.1f متر)",
                topActiveReading.material.titleFa,
                dominantFreq,
                snrDb,
                depthTelemetry.estimatedDepthMeters
            )
            topActiveReading != null && anomalyScore >= 40f -> String.format(
                Locale.US,
                "پالس نیمه‌پایدار %s در %.0f Hz — در حال پایش عمق و تثبیت طیف",
                topActiveReading.material.titleFa,
                dominantFreq
            )
            rmsDbfs < -74f -> "سطح سیگنال ورودی جک/آنتن بسیار ضعیف است — اتصال پروب یا گین را بررسی کنید"
            else -> String.format(
                Locale.US,
                "اسکن پسیو %d kHz پایدار — بدون تداخل غیرعادی در باندهای فعال",
                sampleRateHz / 1000
            )
        }

        return AudioStftResult(
            sampleRateHz = sampleRateHz,
            nyquistHz = nyquist,
            dominantFrequencyHz = dominantFreq,
            peakMagnitude = enabledMaxMag.toFloat(),
            snrDb = snrDb,
            rmsDbfs = rmsDbfs,
            anomalyScore = anomalyScore,
            spectrumVicoDb = spectrumVicoDb,
            noiseFloorVicoDb = noiseVicoDb,
            spectrumAxisLabelsHz = axisLabelsHz,
            waterfallBandRow = waterfallRow,
            waveformPreview = waveformPreview,
            materialReadings = readings,
            topMatchedMaterial = topActiveReading?.material,
            depthTelemetry = depthTelemetry,
            analysisVerdict = verdict
        )
    }

    private fun computeDepthTelemetry(
        hasValidLock: Boolean,
        dominantFreqHz: Float,
        snrDb: Float,
        qFactor: Float,
        elfPct: Float,
        voidPct: Float,
        telluricPct: Float,
        highVlfPct: Float,
        topMaterial: PassiveMaterialId?
    ): PassiveDepthTelemetry {
        if (!hasValidLock) {
            return PassiveDepthTelemetry(
                hasValidLock = false,
                estimatedDepthMeters = 0f,
                depthErrorMarginMeters = 0f,
                depthZoneFa = "در انتظار قفل هدف معتبر",
                inferredGeometryFa = "---",
                qFactor = qFactor,
                elfEnergyPct = elfPct,
                voidEnergyPct = voidPct,
                telluricEnergyPct = telluricPct,
                highVlfEnergyPct = highVlfPct
            )
        }

        val lowFraction = ((elfPct + voidPct) / 100f).coerceIn(0.05f, 1.0f)
        val highFraction = ((telluricPct + highVlfPct) / 100f).coerceIn(0.05f, 1.0f)
        val dispersionRatio = (lowFraction / highFraction).coerceIn(0.2f, 6.0f)

        // Skin-depth scaling: lower resonant frequency + higher LF/HF ratio indicates deeper burial
        val freqDepthComponent = (165.0 / sqrt(dominantFreqHz.toDouble().coerceAtLeast(25.0))).toFloat()
        val rawDepth = (freqDepthComponent * 0.22f * (0.65f + 0.35f * sqrt(dispersionRatio)))
            .coerceIn(0.25f, 7.8f)

        val errMargin = ((1.2f - (snrDb / 45f).coerceIn(0f, 0.8f)) * (if (isCalibrated) 0.7f else 1.15f))
            .coerceIn(0.15f, 1.4f)

        val zone = when {
            rawDepth < 0.6f -> "سطحی (۰ تا ۰.۶ متر)"
            rawDepth < 2.5f -> "میانی (۰.۶ تا ۲.۵ متر)"
            else -> "عمقی / لایه زیرین (۲.۵ تا ۸ متر)"
        }

        val geometry = when (topMaterial) {
            PassiveMaterialId.VOID_CAVITY -> "حفره / گنبد آکوستیک متقارن زیرسطحی"
            PassiveMaterialId.GOLD_DIAMAG, PassiveMaterialId.SILVER_HIGH_COND ->
                if (qFactor > 4.5f) "توده فلزی متمرکز با دوقطبی القایی تیز" else "هاله رسانای تجمعی در عمق"
            PassiveMaterialId.COPPER_BRONZE -> "توده آلیاژی / پوسته اکسید لایه‌ای"
            PassiveMaterialId.FERROUS_IRON -> "قطعه فرومغناطیس سطحی / تداخل آهنی"
            PassiveMaterialId.VLF_TELLURIC -> "رگه کانی خطی / شکستگی تلوریک"
            null -> "---"
        }

        return PassiveDepthTelemetry(
            hasValidLock = true,
            estimatedDepthMeters = rawDepth,
            depthErrorMarginMeters = errMargin,
            depthZoneFa = zone,
            inferredGeometryFa = geometry,
            qFactor = qFactor,
            elfEnergyPct = elfPct,
            voidEnergyPct = voidPct,
            telluricEnergyPct = telluricPct,
            highVlfEnergyPct = highVlfPct
        )
    }

    private fun computeRawMagnitudeSpectrum(pcmBuffer: ShortArray, offset: Int, length: Int): Float {
        val usable = min(length, fftSize)
        var mean = 0.0
        var sumSq = 0.0
        for (i in 0 until usable) {
            val s = pcmBuffer[offset + i].toDouble() / 32768.0
            mean += s
            sumSq += s * s
        }
        mean /= usable.coerceAtLeast(1)
        val rms = sqrt(sumSq / usable.coerceAtLeast(1)).coerceAtLeast(1e-6)
        val rmsDbfs = (20.0 * log10(rms)).toFloat().coerceIn(-110f, 0f)

        for (i in 0 until fftSize) {
            if (i < usable) {
                val centered = (pcmBuffer[offset + i].toDouble() / 32768.0) - mean
                real[i] = centered * hannWindow[i]
            } else {
                real[i] = 0.0
            }
            imag[i] = 0.0
        }

        fftInPlace(real, imag, fftSize)

        val invNorm = 2.0 / fftSize.toDouble()
        for (k in 0 until halfBins) {
            magSpectrum[k] = sqrt(real[k] * real[k] + imag[k] * imag[k]) * invNorm
        }
        return rmsDbfs
    }

    private fun buildWaveformPreview(pcmBuffer: ShortArray, offset: Int, length: Int): List<Float> {
        val previewCount = 96
        if (length <= 0) return emptyList()
        val step = max(1, length / previewCount)
        val out = ArrayList<Float>(previewCount)
        var idx = offset
        val limit = min(pcmBuffer.size, offset + length)
        while (idx < limit && out.size < previewCount) {
            out.add((pcmBuffer[idx].toFloat() / 32768f).coerceIn(-1f, 1f))
            idx += step
        }
        return out
    }

    private fun emptyResult(nyquist: Int): AudioStftResult {
        return AudioStftResult(
            sampleRateHz = sampleRateHz,
            nyquistHz = nyquist,
            dominantFrequencyHz = 0f,
            peakMagnitude = 0f,
            snrDb = 0f,
            rmsDbfs = -100f,
            anomalyScore = 0f,
            spectrumVicoDb = List(vicoBinCount) { 4.0 },
            noiseFloorVicoDb = List(vicoBinCount) { 4.0 },
            spectrumAxisLabelsHz = List(vicoBinCount) { i ->
                (20.0 * (nyquist / 20.0).pow(i.toDouble() / vicoBinCount)).toFloat()
            },
            waterfallBandRow = List(waterfallBinCount) { 0f },
            waveformPreview = emptyList(),
            materialReadings = PassiveMaterialId.entries.map {
                MaterialBandReading(it, true, 0, 0f, 0f)
            },
            topMatchedMaterial = null,
            depthTelemetry = PassiveDepthTelemetry(
                hasValidLock = false,
                estimatedDepthMeters = 0f,
                depthErrorMarginMeters = 0f,
                depthZoneFa = "آماده اسکن 192kHz",
                inferredGeometryFa = "---",
                qFactor = 1f,
                elfEnergyPct = 0f,
                voidEnergyPct = 0f,
                telluricEnergyPct = 0f,
                highVlfEnergyPct = 0f
            ),
            analysisVerdict = "آماده دریافت سیگنال پسیو 192kHz از جک صوتی یا آنتن خارجی..."
        )
    }

    private fun fftInPlace(real: DoubleArray, imag: DoubleArray, n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var l = 2
        while (l <= n) {
            val angle = -2.0 * PI / l
            val wStepR = cos(angle)
            val wStepI = sin(angle)
            var wR = 1.0
            var wI = 0.0

            val halfL = l / 2
            for (m in 0 until halfL) {
                var i = m
                while (i < n) {
                    val idx = i + halfL
                    val tr = wR * real[idx] - wI * imag[idx]
                    val ti = wR * imag[idx] + wI * real[idx]
                    real[idx] = real[i] - tr
                    imag[idx] = imag[i] - ti
                    real[i] += tr
                    imag[i] += ti
                    i += l
                }
                val nextWr = wR * wStepR - wI * wStepI
                val nextWi = wR * wStepI + wI * wStepR
                wR = nextWr
                wI = nextWi
            }
            l = l shl 1
        }
    }
}
