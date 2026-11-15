package com.example.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class SurfaceSoilNoiseEstimate(
    val latestRawDeltaUt: Double,
    val deepEnvelopeUt: Double,
    val shallowClutterUt: Double,
    val soilFootprintWidthM: Double,
    val highClutterRatio: Double,
    val peakSharpness: Double
)

data class AdaptiveDepthFilterResult(
    val rawDeltaUt: Double,
    val deepEnvelopeUt: Double,
    val shallowClutterUt: Double,
    val soilFootprintWidthM: Double,
    val adaptiveThresholdUt: Double,
    val sensitivityGainFactor: Double,
    val surfaceClutterSuppressed: Boolean,
    val filterModeLabel: String,
    val activeSignalUt: Double
)

/**
 * Dedicated Adaptive Depth-Based Filter processor.
 *
 * Receives live magnetometer sensor samples alongside optional STFT sweep metrics and camera
 * hotspot footprint data, calculates the high-frequency surface soil noise / clutter component,
 * estimates soil footprint width, and dynamically adapts magnetometer sensitivity to isolate
 * broader deep target anomalies without being masked by shallow iron/pebble interference.
 */
class AdaptiveDepthFilter(
    private val hannRadius: Int = 7,
    private val clutterTailSamples: Int = 6
) {

    private val hannWeights: DoubleArray = run {
        val span = hannRadius * 2 + 2
        DoubleArray(hannRadius * 2 + 1) { m ->
            0.5 * (1.0 - cos(2.0 * PI * (m + 1) / span))
        }
    }

    /**
     * Separates raw ΔB samples into a broad deep spatial envelope (via boundary-safe symmetric
     * Hann low-pass filtering) and computes the surface soil noise RMS and physical soil footprint.
     */
    fun computeSurfaceSoilNoise(
        samplesUt: List<Double>,
        baselineMeanUt: Double,
        stft: MagStftResult?,
        opticalMeta: ThermalStylePixelResult?,
        sweepSpeedMps: Double = 0.40
    ): SurfaceSoilNoiseEstimate {
        if (samplesUt.isEmpty()) {
            return SurfaceSoilNoiseEstimate(
                latestRawDeltaUt = 0.0,
                deepEnvelopeUt = 0.0,
                shallowClutterUt = 0.0,
                soilFootprintWidthM = 0.0,
                highClutterRatio = 0.0,
                peakSharpness = 0.0
            )
        }

        val n = samplesUt.size
        val deltas = DoubleArray(n) { idx -> samplesUt[idx] - baselineMeanUt }
        val latestRaw = deltas.last()

        val lowPass = DoubleArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            var wSum = 0.0
            for (k in -hannRadius..hannRadius) {
                val j = i + k
                if (j in 0 until n) {
                    val w = hannWeights[k + hannRadius]
                    acc += deltas[j] * w
                    wSum += w
                }
            }
            lowPass[i] = acc / wSum.coerceAtLeast(1e-6)
        }

        val deepEnvelope = lowPass.last()
        val tailCount = min(n, clutterTailSamples)
        var highSqSum = 0.0
        for (i in (n - tailCount) until n) {
            val res = deltas[i] - lowPass[i]
            highSqSum += res * res
        }
        val shallowClutterRms = sqrt(highSqSum / tailCount.coerceAtLeast(1))

        val opticalFootprintM = if (opticalMeta != null && opticalMeta.isLocalizedOpticalAnomaly) {
            val normW = (opticalMeta.hotspotBoxMaxX - opticalMeta.hotspotBoxMinX).coerceIn(0.05f, 1.0f)
            // Handheld camera ~0.85m above soil surface covers ~1.40m horizontal swath
            (normW * 1.40).toDouble()
        } else 0.0

        val stftFootprintM = if (stft != null && stft.estimatedSweepWavelengthM > 0.0) {
            stft.estimatedSweepWavelengthM
        } else if (stft != null && stft.dominantFreqHz > 0.15) {
            (sweepSpeedMps / stft.dominantFreqHz).coerceIn(0.0, 4.5)
        } else 0.0

        val soilFootprintWidthM = when {
            opticalFootprintM > 0.0 && stftFootprintM > 0.0 -> 0.45 * opticalFootprintM + 0.55 * stftFootprintM
            stftFootprintM > 0.0 -> stftFootprintM
            else -> opticalFootprintM
        }.coerceIn(0.0, 4.5)

        val highClutterRatio = shallowClutterRms / (abs(deepEnvelope) + shallowClutterRms + 0.25)
        val peakSharpness = abs(latestRaw) / (abs(deepEnvelope) + 0.35)

        return SurfaceSoilNoiseEstimate(
            latestRawDeltaUt = latestRaw,
            deepEnvelopeUt = deepEnvelope,
            shallowClutterUt = shallowClutterRms,
            soilFootprintWidthM = soilFootprintWidthM,
            highClutterRatio = highClutterRatio,
            peakSharpness = peakSharpness
        )
    }

    /**
     * Adjusts final magnetometer sensitivity threshold and isolates deep target signal from
     * shallow surface soil clutter.
     *
     * depthBandMode:
     * 0 = Auto Adaptive, 1 = Deep (strict surface clutter suppression), 2 = Mid-Depth, 3 = Shallow Surface
     */
    fun filterAndIsolateDeepSignal(
        samplesUt: List<Double>,
        baselineMeanUt: Double,
        baselineStdUt: Double,
        stft: MagStftResult?,
        opticalMeta: ThermalStylePixelResult?,
        userBaseThresholdUt: Float,
        depthBandMode: Int = 0,
        sweepSpeedMps: Double = 0.40
    ): AdaptiveDepthFilterResult {
        if (samplesUt.isEmpty()) {
            val baseFloor = max(userBaseThresholdUt.toDouble(), 2.0)
            return AdaptiveDepthFilterResult(
                rawDeltaUt = 0.0,
                deepEnvelopeUt = 0.0,
                shallowClutterUt = 0.0,
                soilFootprintWidthM = 0.0,
                adaptiveThresholdUt = baseFloor,
                sensitivityGainFactor = 1.0,
                surfaceClutterSuppressed = false,
                filterModeLabel = modeTitle(depthBandMode),
                activeSignalUt = 0.0
            )
        }

        val noiseEstimate = computeSurfaceSoilNoise(
            samplesUt = samplesUt,
            baselineMeanUt = baselineMeanUt,
            stft = stft,
            opticalMeta = opticalMeta,
            sweepSpeedMps = sweepSpeedMps
        )

        val lowBandRatio = stft?.lowBandEnergyRatio ?: 0.5
        val footprintScale = when {
            noiseEstimate.soilFootprintWidthM >= 1.80 -> 0.64
            noiseEstimate.soilFootprintWidthM >= 0.85 -> 0.78
            noiseEstimate.soilFootprintWidthM > 0.15 -> 0.92
            else -> 1.0
        }
        val modeMultiplier = when (depthBandMode) {
            1 -> 0.68 // Deep priority: track weaker broad deep halos
            2 -> 0.85 // Mid depth
            3 -> 1.05 // Shallow surface: keep full high threshold
            else -> if (lowBandRatio >= 0.60) 0.75 else footprintScale
        }
        val safeNoiseFloor = max(baselineStdUt * 2.2, 1.8)
        val adaptedFloor = max(
            userBaseThresholdUt.toDouble() * modeMultiplier * footprintScale.coerceAtLeast(0.75),
            safeNoiseFloor
        )

        val suppressSurfaceClutter = when (depthBandMode) {
            3 -> false
            1 -> (noiseEstimate.peakSharpness > 2.05 && noiseEstimate.highClutterRatio > 0.50) ||
                (noiseEstimate.highClutterRatio > 0.68 && lowBandRatio < 0.45)
            else -> (noiseEstimate.peakSharpness > 2.35 && noiseEstimate.highClutterRatio > 0.58) ||
                (noiseEstimate.highClutterRatio > 0.76 && lowBandRatio < 0.38)
        }

        val activeSignal = when {
            depthBandMode == 3 -> noiseEstimate.latestRawDeltaUt
            suppressSurfaceClutter -> noiseEstimate.deepEnvelopeUt * 0.45
            depthBandMode == 1 || (depthBandMode == 0 && noiseEstimate.soilFootprintWidthM >= 0.75) ->
                noiseEstimate.deepEnvelopeUt
            else -> 0.70 * noiseEstimate.deepEnvelopeUt + 0.30 * noiseEstimate.latestRawDeltaUt
        }

        val gainFactor = (userBaseThresholdUt.toDouble().coerceAtLeast(1.0) / adaptedFloor.coerceAtLeast(0.5))

        return AdaptiveDepthFilterResult(
            rawDeltaUt = noiseEstimate.latestRawDeltaUt,
            deepEnvelopeUt = noiseEstimate.deepEnvelopeUt,
            shallowClutterUt = noiseEstimate.shallowClutterUt,
            soilFootprintWidthM = noiseEstimate.soilFootprintWidthM,
            adaptiveThresholdUt = adaptedFloor,
            sensitivityGainFactor = gainFactor,
            surfaceClutterSuppressed = suppressSurfaceClutter,
            filterModeLabel = modeTitle(depthBandMode),
            activeSignalUt = activeSignal
        )
    }

    companion object {
        fun modeTitle(mode: Int): String = when (mode) {
            1 -> "عمق بالا (حذف تداخل سطحی)"
            2 -> "عمق میانی (تطبیقی)"
            3 -> "سطحی (پهنای باند کامل)"
            else -> "خودکار (تنظیم بر اساس ردپای خاک)"
        }
    }
}
