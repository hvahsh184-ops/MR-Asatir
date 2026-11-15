package com.example.domain

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class MagnetometerReading(
    val bxUt: Double,
    val byUt: Double,
    val bzUt: Double,
    val magnitudeUt: Double,
    val timestampMs: Long
)

data class MagStftResult(
    val frequenciesHz: List<Double>,
    val magnitudesUt: List<Double>,
    val dominantFreqHz: Double,
    val peakAmplitudeUt: Double,
    val spectralCentroidHz: Double,
    val lowBandEnergyRatio: Double,
    val estimatedSweepWavelengthM: Double,
    val apparentMaxDepthM: Double,
    val anomalyClassLabel: String
)

data class OpticalGridStats(
    val cols: Int,
    val rows: Int,
    val ironChromaGrid: FloatArray,
    val gradientGrid: FloatArray,
    val luminanceGrid: FloatArray,
    val meanLuminance: Float,
    val peakChromaIndex: Float,
    val edgeDensity: Float
)

data class ThermalStylePixelResult(
    val argbPixels: IntArray,
    val width: Int,
    val height: Int,
    val peakSurfaceContrastZ: Float,
    val hotspotCoveragePct: Float,
    val meanLuminance: Float,
    val peakChromaIndex: Float,
    val edgeDensity: Float,
    val hotspotCentroidX: Float = 0.5f,
    val hotspotCentroidY: Float = 0.5f,
    val hotspotBoxMinX: Float = 0.5f,
    val hotspotBoxMinY: Float = 0.5f,
    val hotspotBoxMaxX: Float = 0.5f,
    val hotspotBoxMaxY: Float = 0.5f,
    val compactnessScore: Float = 0f,
    val isLocalizedOpticalAnomaly: Boolean = false,
    val opticalStatusReason: String = "سطح یکنواخت",
    val saroojMatchPct: Float = 0f
)

data class MagnetometerEnvironmentCheck(
    val meanUt: Double,
    val stdDevUt: Double,
    val isIndoorInterferenceSuspected: Boolean,
    val warningReason: String?,
    val effectiveFloorUt: Double
)

data class DetectionAlertVerdict(
    val level: Int, // 0 = Safe/Normal, 1 = Camera Only, 2 = Mag Only, 3 = Dual Locked
    val title: String,
    val detail: String
)

data class FieldAnomalyPin(
    val id: Long,
    val timestampMs: Long,
    val angleTag: String,
    val bxUt: Double,
    val byUt: Double,
    val bzUt: Double,
    val magnitudeUt: Double,
    val deltaUt: Double,
    val zScore: Double,
    val yawDeg: Double,
    val latitude: Double?,
    val longitude: Double?,
    val peakOpticalChroma: Float?,
    val edgeDensity: Float?,
    val stftPeakHz: Double? = null,
    val apparentDepthM: Double? = null,
    val snapshot: Bitmap?
)

object FieldReceiverMath {

    fun isPhoneLevelForGroundSweep(pitchDeg: Double, rollDeg: Double, maxTiltDeg: Double = 15.0): Boolean {
        return abs(pitchDeg) <= maxTiltDeg && abs(rollDeg) <= maxTiltDeg
    }

    fun computeMagnitude(bxUt: Double, byUt: Double, bzUt: Double): Double {
        return sqrt(bxUt * bxUt + byUt * byUt + bzUt * bzUt)
    }

    /**
     * Returns Pair(deltaUt, zScore) comparing instantaneous |B| against ambient baseline.
     */
    fun computeAnomaly(
        magnitudeUt: Double,
        baselineMeanUt: Double,
        baselineStdUt: Double
    ): Pair<Double, Double> {
        val delta = magnitudeUt - baselineMeanUt
        val denom = max(baselineStdUt, 0.35)
        val z = abs(delta) / denom
        return Pair(delta, z)
    }

    /**
     * Computes a real Hann-windowed Short-Time Fourier Transform (STFT) on the trailing window
     * of magnetometer |B| samples and converts dominant spatial sweep frequency into an honest
     * theoretical dipole half-width depth envelope (assuming steady handheld sweep velocity).
     */
    fun computeMagnetometerStft(
        samplesUt: List<Double>,
        sampleRateHz: Double = 25.0,
        sweepSpeedMps: Double = 0.4,
        currentDeltaUt: Double = 0.0,
        baselineStdUt: Double = 0.6
    ): MagStftResult {
        val window = samplesUt.takeLast(64)
        val n = window.size
        if (n < 16 || sampleRateHz <= 1.0) {
            return MagStftResult(
                frequenciesHz = emptyList(),
                magnitudesUt = emptyList(),
                dominantFreqHz = 0.0,
                peakAmplitudeUt = 0.0,
                spectralCentroidHz = 0.0,
                lowBandEnergyRatio = 0.0,
                estimatedSweepWavelengthM = 0.0,
                apparentMaxDepthM = 0.0,
                anomalyClassLabel = "جمع‌آوری نمونه اولیه برای STFT ($n/16)"
            )
        }

        val mean = window.average()
        val hann = DoubleArray(n)
        var hannSum = 0.0
        for (i in 0 until n) {
            val w = 0.5 * (1.0 - cos((2.0 * PI * i) / (n - 1).coerceAtLeast(1)))
            hann[i] = w
            hannSum += w
        }
        val normFactor = 2.0 / hannSum.coerceAtLeast(1e-6)

        val maxBin = (n / 2).coerceAtMost(24)
        val freqs = ArrayList<Double>(maxBin)
        val mags = ArrayList<Double>(maxBin)

        var peakAmp = 0.0
        var domFreq = 0.0
        var totalEnergy = 0.0
        var lowBandEnergy = 0.0
        var weightedFreqSum = 0.0
        var ampSum = 0.0

        for (k in 1..maxBin) {
            val freq = (k * sampleRateHz) / n.toDouble()
            if (freq > 10.5) break
            var re = 0.0
            var im = 0.0
            val angleStep = (2.0 * PI * k) / n.toDouble()
            for (i in 0 until n) {
                val x = (window[i] - mean) * hann[i]
                val theta = angleStep * i
                re += x * cos(theta)
                im -= x * sin(theta)
            }
            val amp = sqrt(re * re + im * im) * normFactor
            freqs.add(freq)
            mags.add(amp)

            val e = amp * amp
            totalEnergy += e
            if (freq <= 1.5) lowBandEnergy += e
            weightedFreqSum += freq * amp
            ampSum += amp
            if (amp > peakAmp) {
                peakAmp = amp
                domFreq = freq
            }
        }

        val centroid = if (ampSum > 1e-6) weightedFreqSum / ampSum else 0.0
        val lowRatio = if (totalEnergy > 1e-9) (lowBandEnergy / totalEnergy).coerceIn(0.0, 1.0) else 0.0

        val noiseFloorUt = max(baselineStdUt * 1.4, 0.85)
        val hasSignificantAnomaly = peakAmp >= noiseFloorUt || abs(currentDeltaUt) >= noiseFloorUt * 2.0

        val wavelengthM = if (hasSignificantAnomaly && domFreq > 0.15) {
            (sweepSpeedMps / domFreq).coerceIn(0.08, 4.5)
        } else 0.0

        // Peter's / Dipole half-width spatial relation: z_max ≈ 0.5 * (FWHM) ≈ λ / 4 for steady sweep
        // Clamped realistically to smartphone magnetometer physical envelope (~1.5m max for large iron/kilns).
        val apparentDepthM = if (hasSignificantAnomaly && wavelengthM > 0.0) {
            (wavelengthM * 0.25).coerceIn(0.05, 1.50)
        } else 0.0

        val label = when {
            !hasSignificantAnomaly -> "سطح خاک یکنواخت (بدون اثر مغناطیسی شاخص)"
            domFreq >= 2.2 -> "اثر سطحی متمرکز روی خاک — پالس تیز فرکانس بالا (عرض اثر ~${"%.2f".format(wavelengthM)} متر)"
            domFreq >= 0.85 -> "هاله اثر سطحی میانی روی خاک — تغییر بافت/کانی (عرض ~${"%.2f".format(wavelengthM)} متر)"
            else -> "هاله اثر سطحی پهن روی خاک — ساختار گسترده سطحی (عرض ~${"%.2f".format(wavelengthM)} متر)"
        }

        return MagStftResult(
            frequenciesHz = freqs,
            magnitudesUt = mags,
            dominantFreqHz = domFreq,
            peakAmplitudeUt = peakAmp,
            spectralCentroidHz = centroid,
            lowBandEnergyRatio = lowRatio,
            estimatedSweepWavelengthM = wavelengthM,
            apparentMaxDepthM = apparentDepthM,
            anomalyClassLabel = label
        )
    }

    /**
     * Computes real optical luminance gradient and iron-oxide/mineral chroma contrast
     * from CameraX YUV_420_888 plane bytes across a coarse spatial grid (no fake thermal data).
     */
    fun analyzeYuvFrameGrid(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        gridCols: Int = 16,
        gridRows: Int = 12
    ): OpticalGridStats {
        val totalCells = gridCols * gridRows
        val lumSum = FloatArray(totalCells)
        val chromaSum = FloatArray(totalCells)
        val counts = IntArray(totalCells)

        val stepX = (width / (gridCols * 3)).coerceAtLeast(1)
        val stepY = (height / (gridRows * 3)).coerceAtLeast(1)

        var totalLum = 0f
        var totalSamples = 0

        for (y in 0 until height step stepY) {
            val cellRow = ((y * gridRows) / height).coerceIn(0, gridRows - 1)
            val yRowOffset = y * yRowStride
            val uvRowOffset = (y / 2) * uvRowStride

            for (x in 0 until width step stepX) {
                val yIdx = yRowOffset + x
                val uvIdx = uvRowOffset + (x / 2) * uvPixelStride
                if (yIdx >= yBytes.size || uvIdx >= uBytes.size || uvIdx >= vBytes.size) continue

                val yVal = (yBytes[yIdx].toInt() and 0xFF) / 255f
                val uVal = ((uBytes[uvIdx].toInt() and 0xFF) - 128) / 128f
                val vVal = ((vBytes[uvIdx].toInt() and 0xFF) - 128) / 128f

                // V positive = reddish/iron-oxide hue, U negative = warm yellow/ochre vs blue
                val chromaIndex = ((vVal - 0.5f * uVal) * 0.5f + 0.5f).coerceIn(0f, 1f)

                val cellCol = ((x * gridCols) / width).coerceIn(0, gridCols - 1)
                val cellIdx = cellRow * gridCols + cellCol
                lumSum[cellIdx] += yVal
                chromaSum[cellIdx] += chromaIndex
                counts[cellIdx] += 1

                totalLum += yVal
                totalSamples += 1
            }
        }

        val meanLumCells = FloatArray(totalCells)
        val ironChromaGrid = FloatArray(totalCells)
        var peakChroma = 0f

        for (i in 0 until totalCells) {
            val c = counts[i].coerceAtLeast(1).toFloat()
            meanLumCells[i] = lumSum[i] / c
            ironChromaGrid[i] = chromaSum[i] / c
            if (ironChromaGrid[i] > peakChroma) {
                peakChroma = ironChromaGrid[i]
            }
        }

        val gradientGrid = FloatArray(totalCells)
        var edgeAccum = 0f
        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                val idx = r * gridCols + c
                val left = meanLumCells[r * gridCols + (c - 1).coerceAtLeast(0)]
                val right = meanLumCells[r * gridCols + (c + 1).coerceAtMost(gridCols - 1)]
                val up = meanLumCells[(r - 1).coerceAtLeast(0) * gridCols + c]
                val down = meanLumCells[(r + 1).coerceAtMost(gridRows - 1) * gridCols + c]
                val g = sqrt((right - left) * (right - left) + (down - up) * (down - up)).coerceIn(0f, 1f)
                gradientGrid[idx] = g
                edgeAccum += g
            }
        }

        return OpticalGridStats(
            cols = gridCols,
            rows = gridRows,
            ironChromaGrid = ironChromaGrid,
            gradientGrid = gradientGrid,
            luminanceGrid = meanLumCells,
            meanLuminance = if (totalSamples > 0) totalLum / totalSamples else 0f,
            peakChromaIndex = peakChroma,
            edgeDensity = edgeAccum / totalCells.coerceAtLeast(1)
        )
    }

    private val IRONBOW_LUT: IntArray by lazy {
        val stops = arrayOf(
            intArrayOf(5, 3, 18),      // 0.00 - Deep cold black-indigo
            intArrayOf(42, 11, 92),    // 0.20 - Indigo/violet
            intArrayOf(138, 28, 108),  // 0.40 - Thermal magenta
            intArrayOf(220, 72, 41),   // 0.62 - Crimson-orange
            intArrayOf(250, 184, 34),  // 0.82 - Bright yellow
            intArrayOf(255, 252, 232)  // 1.00 - White-hot peak
        )
        val stopPos = floatArrayOf(0f, 0.20f, 0.40f, 0.62f, 0.82f, 1.0f)
        IntArray(256) { i ->
            val t = i / 255f
            var s = 0
            while (s < stopPos.size - 2 && t > stopPos[s + 1]) s++
            val denom = (stopPos[s + 1] - stopPos[s]).coerceAtLeast(1e-4f)
            val f = ((t - stopPos[s]) / denom).coerceIn(0f, 1f)
            val r = (stops[s][0] + f * (stops[s + 1][0] - stops[s][0])).toInt().coerceIn(0, 255)
            val g = (stops[s][1] + f * (stops[s + 1][1] - stops[s][1])).toInt().coerceIn(0, 255)
            val b = (stops[s][2] + f * (stops[s + 1][2] - stops[s][2])).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private val COOL_WARM_HALO_LUT: IntArray by lazy {
        val stops = arrayOf(
            intArrayOf(0, 48, 140),    // Damp/dark topsoil halo -> deep blue
            intArrayOf(0, 195, 255),   // Cool moist boundary -> cyan
            intArrayOf(26, 28, 36),    // Neutral average background -> dark slate
            intArrayOf(255, 168, 0),   // Dry mineral/carbonate halo -> amber
            intArrayOf(255, 46, 84)    // High contrast anomaly -> crimson
        )
        val stopPos = floatArrayOf(0f, 0.28f, 0.50f, 0.74f, 1.0f)
        IntArray(256) { i ->
            val t = i / 255f
            var s = 0
            while (s < stopPos.size - 2 && t > stopPos[s + 1]) s++
            val denom = (stopPos[s + 1] - stopPos[s]).coerceAtLeast(1e-4f)
            val f = ((t - stopPos[s]) / denom).coerceIn(0f, 1f)
            val r = (stops[s][0] + f * (stops[s + 1][0] - stops[s][0])).toInt().coerceIn(0, 255)
            val g = (stops[s][1] + f * (stops[s + 1][1] - stops[s][1])).toInt().coerceIn(0, 255)
            val b = (stops[s][2] + f * (stops[s + 1][2] - stops[s][2])).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Real full-frame pixel-by-pixel thermal-style visual anomaly processor operating strictly on
     * live CameraX YUV_420_888 pixels (rotation-aligned). Uses a Summed-Area Table (Integral Image)
     * for O(1) local background subtraction (canceling uneven shadow/sunlight gradients) combined
     * with Iron-Oxide chroma shift and MSX-style high-pass surface micro-relief fusion.
     * mode: 1 = Ironbow Thermal-Style Surface Anomaly, 2 = Moisture/Lime Halo, 3 = Oxide & Crack Edge Halo.
     */
    fun computeThermalStyleSurfacePixels(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        rotationDegrees: Int = 0,
        mode: Int = 1,
        targetLongSide: Int = 280,
        gain: Float = 1.6f
    ): ThermalStylePixelResult {
        val rot = ((rotationDegrees % 360) + 360) % 360
        val rotatedPortrait = rot == 90 || rot == 270
        val dispW = if (rotatedPortrait) srcHeight else srcWidth
        val dispH = if (rotatedPortrait) srcWidth else srcHeight

        val scale = targetLongSide.toFloat() / max(dispW, dispH).coerceAtLeast(1).toFloat()
        val outW = (dispW * scale).toInt().coerceIn(32, 320)
        val outH = (dispH * scale).toInt().coerceIn(32, 320)
        val total = outW * outH

        val lumBuf = FloatArray(total)
        val oxideBuf = FloatArray(total)
        val chromaSatBuf = FloatArray(total)

        var lumSum = 0f
        var oxSum = 0f
        var peakChroma = 0f

        for (oy in 0 until outH) {
            val ny = oy.toFloat() / (outH - 1).coerceAtLeast(1)
            for (ox in 0 until outW) {
                val nx = ox.toFloat() / (outW - 1).coerceAtLeast(1)
                val (sxF, syF) = when (rot) {
                    90 -> Pair(ny * (srcWidth - 1), (1f - nx) * (srcHeight - 1))
                    180 -> Pair((1f - nx) * (srcWidth - 1), (1f - ny) * (srcHeight - 1))
                    270 -> Pair((1f - ny) * (srcWidth - 1), nx * (srcHeight - 1))
                    else -> Pair(nx * (srcWidth - 1), ny * (srcHeight - 1))
                }
                val sx = sxF.toInt().coerceIn(0, srcWidth - 1)
                val sy = syF.toInt().coerceIn(0, srcHeight - 1)
                val yIdx = sy * yRowStride + sx
                val uvIdx = (sy / 2) * uvRowStride + (sx / 2) * uvPixelStride

                val yVal = if (yIdx in yBytes.indices) (yBytes[yIdx].toInt() and 0xFF) / 255f else 0.5f
                val uVal = if (uvIdx in uBytes.indices) ((uBytes[uvIdx].toInt() and 0xFF) - 128) / 128f else 0f
                val vVal = if (uvIdx in vBytes.indices) ((vBytes[uvIdx].toInt() and 0xFF) - 128) / 128f else 0f

                val oxVal = (vVal - 0.45f * uVal)
                val chromaIdx = (oxVal * 0.5f + 0.5f).coerceIn(0f, 1f)
                if (chromaIdx > peakChroma) peakChroma = chromaIdx

                val idx = oy * outW + ox
                lumBuf[idx] = yVal
                oxideBuf[idx] = oxVal
                chromaSatBuf[idx] = sqrt(uVal * uVal + vVal * vVal)
                lumSum += yVal
                oxSum += oxVal
            }
        }

        val meanLum = lumSum / total.coerceAtLeast(1)
        val meanOx = oxSum / total.coerceAtLeast(1)
        var sqDiff = 0f
        for (i in 0 until total) {
            val d = lumBuf[i] - meanLum
            sqDiff += d * d
        }
        val stdLum = sqrt((sqDiff / total.coerceAtLeast(1)).coerceAtLeast(0.0012f))

        // Build O(1) Summed-Area Tables for local adaptive shadow-compensated soil contrast
        val iw = outW + 1
        val satLum = FloatArray(iw * (outH + 1))
        val satOx = FloatArray(iw * (outH + 1))
        for (oy in 0 until outH) {
            var rowLum = 0f
            var rowOx = 0f
            val srcRow = oy * outW
            val prevSatRow = oy * iw
            val curSatRow = (oy + 1) * iw
            for (ox in 0 until outW) {
                rowLum += lumBuf[srcRow + ox]
                rowOx += oxideBuf[srcRow + ox]
                satLum[curSatRow + ox + 1] = satLum[prevSatRow + ox + 1] + rowLum
                satOx[curSatRow + ox + 1] = satOx[prevSatRow + ox + 1] + rowOx
            }
        }

        val argb = IntArray(total)
        var maxContrastZ = 0f
        var hotspotCount = 0
        var saroojPixelCount = 0
        var edgeAccum = 0f
        val rad = (minOf(outW, outH) / 10).coerceIn(6, 18)
        val clampedGain = gain.coerceIn(0.6f, 3.5f)
        val gridCols = 8
        val gridRows = 6
        val macroEnergy = FloatArray(gridCols * gridRows)
        var totalMacroEnergy = 0f

        for (oy in 0 until outH) {
            val upRow = (oy - 1).coerceAtLeast(0) * outW
            val dnRow = (oy + 1).coerceAtMost(outH - 1) * outW
            val curRow = oy * outW
            val y0 = (oy - rad).coerceAtLeast(0)
            val y1 = (oy + rad).coerceAtMost(outH - 1)
            val satY0 = y0 * iw
            val satY1 = (y1 + 1) * iw
            val by = ((oy * gridRows) / outH).coerceIn(0, gridRows - 1)

            for (ox in 0 until outW) {
                val idx = curRow + ox
                val l = lumBuf[idx]
                val left = lumBuf[curRow + (ox - 1).coerceAtLeast(0)]
                val right = lumBuf[curRow + (ox + 1).coerceAtMost(outW - 1)]
                val up = lumBuf[upRow + ox]
                val down = lumBuf[dnRow + ox]
                val edge = (abs(right - left) + abs(down - up)).coerceIn(0f, 1f)
                edgeAccum += edge

                val x0 = (ox - rad).coerceAtLeast(0)
                val x1 = (ox + rad).coerceAtMost(outW - 1)
                val area = ((x1 - x0 + 1) * (y1 - y0 + 1)).coerceAtLeast(1).toFloat()
                val localMeanLum = (satLum[satY1 + x1 + 1] - satLum[satY0 + x1 + 1] - satLum[satY1 + x0] + satLum[satY0 + x0]) / area
                val localMeanOx = (satOx[satY1 + x1 + 1] - satOx[satY0 + x1 + 1] - satOx[satY1 + x0] + satOx[satY0 + x0]) / area

                val zGlobal = (l - meanLum) / stdLum
                val zLocal = (l - localMeanLum) / stdLum
                val combinedZ = 0.45f * zGlobal + 0.55f * zLocal
                val oxDev = (0.45f * (oxideBuf[idx] - meanOx) + 0.55f * (oxideBuf[idx] - localMeanOx))

                // Real mineral optical index for weathered lime/ash Sarooj mortar:
                // Pale relative to surrounding topsoil (z > +0.9), low saturated clay/veg color (desaturated gray-cream), and hard edge/fracture
                val desat = (1f - (chromaSatBuf[idx] * 4.2f)).coerceIn(0f, 1f)
                val paleLift = ((l - localMeanLum) / stdLum).coerceAtLeast(0f)
                val isSaroojPixel = l >= 0.44f && desat >= 0.52f && (paleLift >= 0.9f || zGlobal >= 1.15f)
                if (isSaroojPixel) saroojPixelCount++

                val absZ = abs(combinedZ)
                if (absZ > maxContrastZ) maxContrastZ = absZ
                val triggerPixel = if (mode == 4) isSaroojPixel else (absZ >= 1.55f || abs(oxDev) >= 0.13f)
                if (triggerPixel) {
                    hotspotCount++
                    val e = if (mode == 4) (paleLift + desat * 2f + edge * 3f) else (absZ + abs(oxDev) * 6.0f)
                    val bx = ((ox * gridCols) / outW).coerceIn(0, gridCols - 1)
                    macroEnergy[by * gridCols + bx] += e
                    totalMacroEnergy += e
                }

                val baseColor = when (mode) {
                    2 -> {
                        val norm = (0.5f + combinedZ * 0.22f * clampedGain - oxDev * 0.45f * clampedGain).coerceIn(0f, 1f)
                        COOL_WARM_HALO_LUT[(norm * 255f).toInt().coerceIn(0, 255)]
                    }
                    3 -> {
                        val base = (l * 95f).toInt().coerceIn(10, 110)
                        val oxBoost = (abs(oxDev) * 4.0f * clampedGain).coerceIn(0f, 1f)
                        val edBoost = (edge * 3.0f * clampedGain).coerceIn(0f, 1f)
                        val r = (base + oxBoost * 190f + edBoost * 65f).toInt().coerceIn(0, 255)
                        val g = (base + edBoost * 185f + oxBoost * 55f).toInt().coerceIn(0, 255)
                        val b = (base + edBoost * 110f).toInt().coerceIn(0, 255)
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    4 -> {
                        // Sarooj / Lime-Ash Mortar mode: dark charcoal clay background + glowing cyan-emerald highlights on pale desaturated mortar + fracture contour
                        val bg = (l * 82f).toInt().coerceIn(12, 92)
                        val saroojStrength = (desat * ((paleLift * 0.55f + edge * 1.35f) * clampedGain)).coerceIn(0f, 1f)
                        val r = (bg + saroojStrength * 95f).toInt().coerceIn(0, 255)
                        val g = (bg + saroojStrength * 235f).toInt().coerceIn(0, 255)
                        val b = (bg + saroojStrength * 215f).toInt().coerceIn(0, 255)
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    else -> {
                        val thermalScore = (0.46f + (combinedZ * 0.20f + oxDev * 1.05f) * clampedGain + edge * 0.22f).coerceIn(0f, 1f)
                        IRONBOW_LUT[(thermalScore * 255f).toInt().coerceIn(0, 255)]
                    }
                }

                // FLIR MSX-style high-pass surface texture embossing so cracks/stones stay crisp
                val msxDelta = ((edge - 0.06f) * 75f).toInt().coerceIn(-18, 42)
                val rOut = (((baseColor shr 16) and 0xFF) + msxDelta).coerceIn(0, 255)
                val gOut = (((baseColor shr 8) and 0xFF) + msxDelta).coerceIn(0, 255)
                val bOut = ((baseColor and 0xFF) + msxDelta).coerceIn(0, 255)
                argb[idx] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }

        val saroojMatchPct = (saroojPixelCount * 100f) / total.coerceAtLeast(1)
        val coveragePct = (hotspotCount * 100f) / total.coerceAtLeast(1)
        var peakCellIdx = 0
        var peakCellEnergy = -1f
        for (i in macroEnergy.indices) {
            if (macroEnergy[i] > peakCellEnergy) {
                peakCellEnergy = macroEnergy[i]
                peakCellIdx = i
            }
        }
        val peakBx = peakCellIdx % gridCols
        val peakBy = peakCellIdx / gridCols
        var clusterEnergy = 0f
        var weightedX = 0f
        var weightedY = 0f
        var minBx = gridCols - 1
        var maxBx = 0
        var minBy = gridRows - 1
        var maxBy = 0
        val cellThreshold = (peakCellEnergy * 0.22f).coerceAtLeast(0.5f)
        for (by in (peakBy - 1).coerceAtLeast(0)..(peakBy + 1).coerceAtMost(gridRows - 1)) {
            for (bx in (peakBx - 1).coerceAtLeast(0)..(peakBx + 1).coerceAtMost(gridCols - 1)) {
                val e = macroEnergy[by * gridCols + bx]
                clusterEnergy += e
                if (e >= cellThreshold) {
                    val cxCell = (bx + 0.5f) / gridCols.toFloat()
                    val cyCell = (by + 0.5f) / gridRows.toFloat()
                    weightedX += cxCell * e
                    weightedY += cyCell * e
                    if (bx < minBx) minBx = bx
                    if (bx > maxBx) maxBx = bx
                    if (by < minBy) minBy = by
                    if (by > maxBy) maxBy = by
                }
            }
        }

        val compactness = if (totalMacroEnergy > 1e-4f) {
            (clusterEnergy / totalMacroEnergy).coerceIn(0f, 1f)
        } else 0f
        val centroidX = if (clusterEnergy > 1e-4f && weightedX > 0f) (weightedX / clusterEnergy).coerceIn(0.05f, 0.95f) else 0.5f
        val centroidY = if (clusterEnergy > 1e-4f && weightedY > 0f) (weightedY / clusterEnergy).coerceIn(0.05f, 0.95f) else 0.5f
        val boxMinX = (minBx.toFloat() / gridCols).coerceIn(0f, 0.9f)
        val boxMaxX = ((maxBx + 1).toFloat() / gridCols).coerceIn((boxMinX + 0.08f).coerceAtMost(1f), 1f)
        val boxMinY = (minBy.toFloat() / gridRows).coerceIn(0f, 0.9f)
        val boxMaxY = ((maxBy + 1).toFloat() / gridRows).coerceIn((boxMinY + 0.08f).coerceAtMost(1f), 1f)

        val (isLocalized, reason) = when {
            coveragePct < 1.0f || maxContrastZ < 2.2f -> Pair(
                false,
                "سطح یکنواخت (بدون لکه یا کنتراست غیرعادی)"
            )
            coveragePct > 28.0f || compactness < 0.42f -> Pair(
                false,
                "نمای شلوغ یا محیط بسته (ناهنجاری پراکنده اتاق/اشیاء؛ هدف موضعی خاک نیست)"
            )
            mode == 4 -> Pair(
                true,
                "احتمال رگه آهک/ساروج موضعی (${String.format(java.util.Locale.US, "%.0f", saroojMatchPct)}% کادر | تراکم ${(compactness * 100).toInt()}٪)"
            )
            else -> Pair(
                true,
                "کانون موضعی روی سطح (${String.format(java.util.Locale.US, "%.0f", coveragePct)}% کادر | تراکم ${(compactness * 100).toInt()}٪)"
            )
        }

        return ThermalStylePixelResult(
            argbPixels = argb,
            width = outW,
            height = outH,
            peakSurfaceContrastZ = maxContrastZ,
            hotspotCoveragePct = coveragePct,
            meanLuminance = meanLum,
            peakChromaIndex = peakChroma,
            edgeDensity = edgeAccum / total.coerceAtLeast(1),
            hotspotCentroidX = centroidX,
            hotspotCentroidY = centroidY,
            hotspotBoxMinX = boxMinX,
            hotspotBoxMinY = boxMinY,
            hotspotBoxMaxX = boxMaxX,
            hotspotBoxMaxY = boxMaxY,
            compactnessScore = compactness,
            isLocalizedOpticalAnomaly = isLocalized,
            opticalStatusReason = reason,
            saroojMatchPct = saroojMatchPct
        )
    }

    fun renderThermalStyleBitmap(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        rotationDegrees: Int,
        mode: Int,
        gain: Float = 1.6f
    ): Pair<Bitmap, ThermalStylePixelResult> {
        val res = computeThermalStyleSurfacePixels(
            yBytes = yBytes,
            uBytes = uBytes,
            vBytes = vBytes,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            yRowStride = yRowStride,
            uvRowStride = uvRowStride,
            uvPixelStride = uvPixelStride,
            rotationDegrees = rotationDegrees,
            mode = mode,
            gain = gain
        )
        val bmp = Bitmap.createBitmap(res.argbPixels, res.width, res.height, Bitmap.Config.ARGB_8888)
        return Pair(bmp, res)
    }

    /**
     * Evaluates a 25-sample baseline calibration window to detect indoor metal/electrical EM hum
     * (rebar, sofa springs, TV, chargers, speakers) before enabling magnetometer detection alerts.
     */
    fun evaluateMagnetometerCalibrationWindow(
        samplesUt: List<Double>,
        userThresholdUt: Float
    ): MagnetometerEnvironmentCheck {
        if (samplesUt.isEmpty()) {
            return MagnetometerEnvironmentCheck(
                meanUt = 45.0,
                stdDevUt = 0.5,
                isIndoorInterferenceSuspected = false,
                warningReason = null,
                effectiveFloorUt = userThresholdUt.toDouble()
            )
        }
        val mean = samplesUt.average()
        val variance = samplesUt.sumOf { (it - mean) * (it - mean) } / samplesUt.size
        val stdDev = sqrt(variance)
        var signFlips = 0
        var prevSign = 0
        for (s in samplesUt) {
            val d = s - mean
            val sg = if (d > 0.25) 1 else if (d < -0.25) -1 else 0
            if (sg != 0 && prevSign != 0 && sg != prevSign) signFlips++
            if (sg != 0) prevSign = sg
        }

        val outOfEarthRange = mean < 22.0 || mean > 68.0
        val noisyEnvironment = stdDev > 1.85 || (signFlips >= 11 && stdDev > 0.65)
        val interference = outOfEarthRange || noisyEnvironment
        val reason = when {
            outOfEarthRange -> String.format(
                java.util.Locale.US,
                "میدان محیط غیرطبیعی است (|B|=%.0f µT؛ میدان طبیعی زمین ۲۵ تا ۶۵ میکروتسلاست) — احتمالاً کنار آهن، مبل، اسپیکر یا میلگرد ساختمان هستید.",
                mean
            )
            noisyEnvironment -> String.format(
                java.util.Locale.US,
                "نویز برق/تداخل اتاق شناسایی شد (نوسان ±%.1f µT) — دور از وسایل برقی و روی خاک تمیز دوباره کالیبره کنید.",
                stdDev
            )
            else -> null
        }
        val floor = if (interference) {
            max(userThresholdUt.toDouble(), max(stdDev * 4.5, 14.0))
        } else {
            max(userThresholdUt.toDouble(), max(stdDev * 3.5, 4.5))
        }
        return MagnetometerEnvironmentCheck(
            meanUt = mean,
            stdDevUt = stdDev,
            isIndoorInterferenceSuspected = interference,
            warningReason = reason,
            effectiveFloorUt = floor
        )
    }

    fun evaluateDetectionAlert(
        isMagBaselineCalibrated: Boolean,
        isMagConfirmed: Boolean,
        isOpticalConfirmed: Boolean,
        isCameraActive: Boolean,
        magDeltaUt: Double,
        opticalMeta: ThermalStylePixelResult?,
        indoorWarning: String?
    ): DetectionAlertVerdict {
        return when {
            isMagConfirmed && isOpticalConfirmed -> DetectionAlertVerdict(
                level = 3,
                title = "هشدار قطعی دوگانه (دوربین + مگنتومتر)!",
                detail = String.format(
                    java.util.Locale.US,
                    "تلاقی واقعی روی هدف: تغییر مغناطیسی %+.1f µT هم‌زمان با کانون موضعی تصویر (Zs %.1f). دقیقاً همین نقطه را ثبت کنید!",
                    magDeltaUt,
                    opticalMeta?.peakSurfaceContrastZ ?: 0f
                )
            )
            isMagConfirmed -> DetectionAlertVerdict(
                level = 2,
                title = "هشدار مگنتومتر: ناهنجاری واقعی تشخیص داده شد!",
                detail = String.format(
                    java.util.Locale.US,
                    "تغییر میدان پایدار %+.1f µT فراتر از خط پایه محیط. گوشی را ۱۰ سانت بالای خاک ثابت ببرید تا تصویر هم قفل شود.",
                    magDeltaUt
                )
            )
            isOpticalConfirmed -> DetectionAlertVerdict(
                level = 1,
                title = "هشدار دوربین: کانون موضعی روی سطح شناسایی شد!",
                detail = opticalMeta?.opticalStatusReason ?: "لکه/کنتراست متمرکز روی خاک ردیابی شد. مگنتومتر را روی کادر زرد نگه دارید."
            )
            !isMagBaselineCalibrated -> DetectionAlertVerdict(
                level = 0,
                title = "قفل ضدخطا: در حال کالیبراسیون صفر محیط",
                detail = "برای جلوگیری از تشخیص اشتباه داخل خانه، چند ثانیه گوشی را ثابت و دور از آهن نگه دارید."
            )
            indoorWarning != null -> DetectionAlertVerdict(
                level = 0,
                title = "محافظ ضدخطای محیط بسته فعال است",
                detail = indoorWarning
            )
            isCameraActive && opticalMeta != null && !opticalMeta.isLocalizedOpticalAnomaly -> DetectionAlertVerdict(
                level = 0,
                title = "اسکن عادی — ناهنجاری تاییدشده‌ای نیست",
                detail = opticalMeta.opticalStatusReason
            )
            else -> DetectionAlertVerdict(
                level = 0,
                title = "اسکن طبیعی — محیط پاک است",
                detail = "هیچ نوسان مغناطیسی پایدار یا کانون موضعی سطحی پیدا نشد."
            )
        }
    }

    fun computeRamUsageMetrics(usedBytes: Long, maxBytes: Long): Triple<Int, Int, Int> {
        val safeMax = maxBytes.coerceAtLeast(1024L * 1024L)
        val clampedUsed = usedBytes.coerceIn(0L, safeMax)
        val pct = ((clampedUsed * 100.0) / safeMax).toInt().coerceIn(0, 100)
        val usedMb = (clampedUsed / (1024L * 1024L)).toInt()
        val maxMb = (safeMax / (1024L * 1024L)).toInt()
        return Triple(pct, usedMb, maxMb)
    }

    fun shouldAutoCleanRam(usagePercent: Int, thresholdPercent: Int = 90): Boolean {
        return usagePercent >= thresholdPercent
    }
}
