package com.example

import com.example.domain.RadarMathEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RadarMathEngineTest {

    @Test
    fun matrixInversion_recoversIdentityProduct() {
        val mat = arrayOf(
            doubleArrayOf(4.0, 1.0, 0.5),
            doubleArrayOf(1.0, 3.0, 0.2),
            doubleArrayOf(0.5, 0.2, 2.0)
        )
        val inv = RadarMathEngine.invertMatrix(mat)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var sum = 0.0
                for (k in 0 until 3) sum += mat[r][k] * inv[k][c]
                val expected = if (r == c) 1.0 else 0.0
                assertEquals(expected, sum, 1e-6)
            }
        }
    }

    @Test
    fun mahalanobisAnalysis_detectsInjectedSurfaceAnomalyCell() {
        val centerLat = 35.6892
        val centerLon = 51.3890
        val grid = RadarMathEngine.generateSpatialGrid(centerLat, centerLon, 600.0, 7)
        val elevations = MutableList(49) { 1200.0 }
        // Inject localized surface mound at row=2, col=4 (index = 18)
        elevations[18] = 1212.5

        val summary = RadarMathEngine.analyzeSurfaceMatrix(
            gridPoints = grid,
            elevationsM = elevations,
            centerLat = centerLat,
            centerLon = centerLon,
            radiusMeters = 600.0,
            gridSize = 7
        )

        assertEquals(2, summary.peakAnomalyCell.row)
        assertEquals(4, summary.peakAnomalyCell.col)
        assertTrue(summary.peakAnomalyCell.mahalanobisDistance > 3.0)
    }

    @Test
    fun geodeticOffset_roundTripsWithUtmAndOffsetCoordinate() {
        val lat = 35.6892
        val lon = 51.3890
        val shifted = RadarMathEngine.offsetCoordinate(lat, lon, deltaEastM = 120.0, deltaNorthM = -80.0)
        val offset = RadarMathEngine.computeGeodeticOffset(lat, lon, shifted.latitude, shifted.longitude)

        assertTrue(abs(offset.deltaEastMeters - 120.0) < 0.5)
        assertTrue(abs(offset.deltaNorthMeters + 80.0) < 0.5)
        assertEquals(39, offset.fromUtm.zone)
        assertEquals('N', offset.fromUtm.hemisphere)
    }

    @Test
    fun atmosphericCorrection_computesRealisticTroposphericDelay() {
        val atm = RadarMathEngine.computeAtmosphericCorrection(
            pressureHpa = 885.0,
            temperatureC = 22.0,
            relativeHumidityPct = 40.0,
            soilMoistureM3 = 0.14,
            centerLat = 35.6892,
            meanElevationM = 1200.0
        )
        assertTrue(atm.zenithHydrostaticDelayM in 1.8..2.3)
        assertTrue(atm.zenithWetDelayM in 0.02..0.25)
        assertTrue(atm.slantRangeDelayM > atm.zenithHydrostaticDelayM)
    }

    @Test
    fun sarOpticalFusion_detectsCleanPriorityTargetAndFiltersSalineVeg() {
        val dim = 21
        val total = dim * dim
        val centerLat = 35.1709667
        val centerLon = 49.6665633
        val dDeg = 0.5 / 111.0
        val dem = FloatArray(total) { 1600f }
        val asc = FloatArray(total) { 0.08f }
        val desc = FloatArray(total) { 0.08f }
        val green = FloatArray(total) { 0.10f }
        val red = FloatArray(total) { 0.14f }
        val nir = FloatArray(total) { 0.16f } // NDVI ~ 0.067 < 0.22
        val swir1 = FloatArray(total) { 0.22f } // Salinity ~ 0.37 > 0.05
        val swir2 = FloatArray(total) { 0.20f } // Carb ~ 0.047
        val scl = IntArray(total) { 5 } // Bare soil

        // Inject clean radar CFAR peak + carbonate anomaly at (10, 10)
        val targetIdx = 10 * dim + 10
        asc[targetIdx] = 0.42f
        desc[targetIdx] = 0.41f
        swir1[targetIdx] = 0.38f
        swir2[targetIdx] = 0.12f

        // Inject vegetation-contaminated radar peak at (6, 6) -> should be filtered out
        val vegIdx = 6 * dim + 6
        asc[vegIdx] = 0.44f
        desc[vegIdx] = 0.43f
        nir[vegIdx] = 0.48f
        red[vegIdx] = 0.08f // NDVI ~ 0.71 >= 0.22

        val result = com.example.domain.SarOpticalFusionEngine.runPipeline(
            width = dim,
            height = dim,
            minLon = centerLon - dDeg,
            minLat = centerLat - dDeg,
            maxLon = centerLon + dDeg,
            maxLat = centerLat + dDeg,
            centerLat = centerLat,
            centerLon = centerLon,
            demElevations = dem,
            sarAscendingVv = asc,
            sarAscendingVh = null,
            sarDescendingVv = desc,
            sarDescendingVh = null,
            s2SummerGreen = green,
            s2SummerRed = red,
            s2SummerNir = nir,
            s2SummerSwir1 = swir1,
            s2SummerSwir2 = swir2,
            s2SummerScl = scl,
            powerInfraPoints = emptyList()
        )

        assertTrue(result.ascCfarCount >= 2)
        assertTrue(result.descCfarCount >= 2)
        assertTrue(result.alignmentReport != null)
        assertTrue(result.sarDifference.meanEwOrbitDiff != null)
        assertTrue(result.multispectralStats?.minSpectralAngleDeg != null)
        assertEquals(1, result.cleanRadarPoints.size)
        assertEquals(1, result.priorityTargets.size)
    }

    @Test
    fun twoSeasonSarDifference_coregistersSubtractsAndSuppressesTransientNoise() {
        val dim = 21
        val total = dim * dim
        val centerLat = 35.1709667
        val centerLon = 49.6665633
        val dDeg = 0.5 / 111.0

        val summer = FloatArray(total) { 0.06f }
        val winter = FloatArray(total) { 0.14f } // higher winter background moisture

        // Persistent structural target at (10, 10) in summer and shifted +1 col in winter (10, 11)
        summer[10 * dim + 10] = 0.36f
        summer[10 * dim + 11] = 0.22f
        winter[10 * dim + 11] = 0.44f
        winter[10 * dim + 12] = 0.28f

        // Transient surface noise spike ONLY in summer at (5, 5)
        summer[5 * dim + 5] = 0.38f

        val res = com.example.domain.SarOpticalFusionEngine.analyzeTwoSeasonSarDifference(
            summerSar = summer,
            winterSar = winter,
            width = dim,
            height = dim,
            minLon = centerLon - dDeg,
            minLat = centerLat - dDeg,
            maxLon = centerLon + dDeg,
            maxLat = centerLat + dDeg,
            centerLat = centerLat,
            centerLon = centerLon
        )

        assertTrue(res.coregNccScore > 0.5)
        assertTrue(res.suppressedTransientNoisePct > 0.0)
        assertTrue(res.persistentHotspots.isNotEmpty())
        // Top hotspot should correspond to persistent target near center (10,10), not transient (5,5)
        val top = res.persistentHotspots.first()
        assertTrue(kotlin.math.abs(top.latitude - centerLat) < 0.001)
    }

    @Test
    fun savitzkyGolay2d_suppressesImpulseSpeckleAndPreservesSmoothField() {
        val dim = 15
        val field = FloatArray(dim * dim) { 10.0f }
        // Inject 1-pixel random speckle spike at center
        val centerIdx = 7 * dim + 7
        field[centerIdx] = 50.0f

        val smoothed = com.example.domain.SarOpticalFusionEngine.applySavitzkyGolay2D(field, dim, dim)
        // Impulse noise peak (50.0 -> ~16.2) is heavily attenuated while smooth edges stay 10.0
        assertTrue(smoothed[centerIdx] < 20.0f)
        assertEquals(10.0f, smoothed[3 * dim + 3], 0.01f)
    }

    @Test
    fun multiSensorMahalanobis_detectsJointAnomalyWithSavitzkyGolay() {
        val dim = 21
        val total = dim * dim
        val centerLat = 35.1709667
        val centerLon = 49.6665633
        val dDeg = 0.5 / 111.0
        val dem = FloatArray(total) { 1600f }
        val asc = FloatArray(total) { 0.08f }
        val desc = FloatArray(total) { 0.08f }
        val green = FloatArray(total) { 0.10f }
        val red = FloatArray(total) { 0.14f }
        val nir = FloatArray(total) { 0.16f }
        val swir1 = FloatArray(total) { 0.22f }
        val swir2 = FloatArray(total) { 0.20f }
        val scl = IntArray(total) { 5 }

        // Inject localized 3x3 physical anomaly (SAR + Carbonate + DEM mound) at (12, 11)
        for (dr in -1..1) {
            for (dc in -1..1) {
                val idx = (12 + dr) * dim + (11 + dc)
                asc[idx] = 0.35f
                desc[idx] = 0.34f
                swir1[idx] = 0.39f
                swir2[idx] = 0.13f
                dem[idx] = 1606.0f
            }
        }

        val result = com.example.domain.SarOpticalFusionEngine.runPipeline(
            width = dim,
            height = dim,
            minLon = centerLon - dDeg,
            minLat = centerLat - dDeg,
            maxLon = centerLon + dDeg,
            maxLat = centerLat + dDeg,
            centerLat = centerLat,
            centerLon = centerLon,
            demElevations = dem,
            sarAscendingVv = asc,
            sarAscendingVh = null,
            sarDescendingVv = desc,
            sarDescendingVh = null,
            s2SummerGreen = green,
            s2SummerRed = red,
            s2SummerNir = nir,
            s2SummerSwir1 = swir1,
            s2SummerSwir2 = swir2,
            s2SummerScl = scl
        )

        assertTrue(result.savitzkyGolayApplied)
        assertTrue(result.peakMahalanobisDm > 3.0)
        assertTrue(result.mahalanobisHotspots.isNotEmpty())
        assertTrue((result.mahalanobisHotspots.first().mahalanobisDistance ?: 0.0) > 2.8)
    }

    @Test
    fun gcpShiftCalibration_recoversExactGroundCoordinate() {
        val gcpTrueLat = 35.1720000
        val gcpTrueLon = 49.6680000
        // Simulate systematic image shift of -12m East, +18m North
        val gcpImage = RadarMathEngine.offsetCoordinate(gcpTrueLat, gcpTrueLon, -12.0, 18.0)

        val shift = RadarMathEngine.computeGeodeticOffset(
            fromLat = gcpImage.latitude,
            fromLon = gcpImage.longitude,
            toLat = gcpTrueLat,
            toLon = gcpTrueLon
        )

        val uncalibratedTarget = RadarMathEngine.offsetCoordinate(35.1710000, 49.6660000, -12.0, 18.0)
        val calibrated = RadarMathEngine.offsetCoordinate(
            uncalibratedTarget.latitude,
            uncalibratedTarget.longitude,
            shift.deltaEastMeters,
            shift.deltaNorthMeters
        )

        assertEquals(35.1710000, calibrated.latitude, 1e-5)
        assertEquals(49.6660000, calibrated.longitude, 1e-5)
    }

    @Test
    fun voiceNavigationFormatter_producesAccurateTurnAndDistanceCues() {
        val straight = com.example.sensors.VoiceNavigationFormatter.formatNavigationCue(
            distanceMeters = 42.3,
            targetBearingDeg = 90.0,
            deviceYawDeg = 85.0,
            usePersian = true
        )
        assertTrue(straight.contains("42 متر"))
        assertTrue(straight.contains("شرق"))
        assertTrue(straight.contains("مستقیم"))

        val rightTurn = com.example.sensors.VoiceNavigationFormatter.formatNavigationCue(
            distanceMeters = 18.0,
            targetBearingDeg = 10.0,
            deviceYawDeg = 310.0, // +60 deg relative turn
            usePersian = false
        )
        assertTrue(rightTurn.contains("18 meters"))
        assertTrue(rightTurn.contains("north"))
        assertTrue(rightTurn.contains("right"))

        val leftTurn = com.example.sensors.VoiceNavigationFormatter.formatNavigationCue(
            distanceMeters = 25.0,
            targetBearingDeg = 270.0,
            deviceYawDeg = 350.0, // -80 deg relative turn
            usePersian = true
        )
        assertTrue(leftTurn.contains("25 متر"))
        assertTrue(leftTurn.contains("غرب"))
        assertTrue(leftTurn.contains("چپ"))

        val locked = com.example.sensors.VoiceNavigationFormatter.formatNavigationCue(
            distanceMeters = 2.4,
            targetBearingDeg = 0.0,
            deviceYawDeg = 180.0,
            usePersian = true
        )
        assertTrue(locked.contains("تثبیت نقطه صفر"))
        assertTrue(locked.contains("2.4"))
    }

    @Test
    fun fieldReceiverMath_computesMagnetometerAnomalyAndYuvOpticalGrid() {
        val mag = com.example.domain.FieldReceiverMath.computeMagnitude(30.0, 40.0, 0.0)
        assertEquals(50.0, mag, 1e-6)

        val (delta, zScore) = com.example.domain.FieldReceiverMath.computeAnomaly(
            magnitudeUt = 64.0,
            baselineMeanUt = 46.0,
            baselineStdUt = 0.9
        )
        assertEquals(18.0, delta, 1e-6)
        assertEquals(20.0, zScore, 1e-6)

        val w = 32
        val h = 24
        val y = ByteArray(w * h) { 100.toByte() }
        val u = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val v = ByteArray((w / 2) * (h / 2)) { 128.toByte() }

        // Inject bright iron-tinted patch on right half
        for (row in 0 until h) {
            for (col in w / 2 until w) {
                y[row * w + col] = 220.toByte()
            }
        }
        for (row in 0 until h / 2) {
            for (col in (w / 4) until (w / 2)) {
                u[row * (w / 2) + col] = 80.toByte()
                v[row * (w / 2) + col] = 210.toByte()
            }
        }

        val stats = com.example.domain.FieldReceiverMath.analyzeYuvFrameGrid(
            yBytes = y,
            uBytes = u,
            vBytes = v,
            width = w,
            height = h,
            yRowStride = w,
            uvRowStride = w / 2,
            uvPixelStride = 1,
            gridCols = 8,
            gridRows = 6
        )

        assertTrue(stats.peakChromaIndex > 0.7f)
        assertTrue(stats.edgeDensity > 0.02f)
        assertEquals(48, stats.luminanceGrid.size)
        assertTrue(stats.luminanceGrid.last() > stats.luminanceGrid.first())
        assertTrue(stats.meanLuminance > 0.5f)
        assertTrue(com.example.domain.FieldReceiverMath.isPhoneLevelForGroundSweep(5.0, -10.0))
        assertTrue(!com.example.domain.FieldReceiverMath.isPhoneLevelForGroundSweep(22.0, 0.0))
    }

    @Test
    fun magnetometerStft_detectsDominantSweepFrequencyAndCalmBaseline() {
        val sampleRate = 25.0
        val targetFreq = 1.95 // ~1.95 Hz sweep pulse over 64 samples
        val samples = List(64) { i ->
            46.0 + 12.0 * kotlin.math.sin(2.0 * kotlin.math.PI * targetFreq * (i / sampleRate))
        }
        val stft = com.example.domain.FieldReceiverMath.computeMagnetometerStft(
            samplesUt = samples,
            sampleRateHz = sampleRate,
            sweepSpeedMps = 0.4,
            currentDeltaUt = 10.0,
            baselineStdUt = 0.5
        )
        assertTrue(stft.frequenciesHz.isNotEmpty())
        assertTrue(kotlin.math.abs(stft.dominantFreqHz - targetFreq) < 0.45)
        assertTrue(stft.peakAmplitudeUt > 7.0)
        assertTrue(stft.apparentMaxDepthM in 0.04..0.25)

        val calm = List(64) { 46.0 }
        val calmStft = com.example.domain.FieldReceiverMath.computeMagnetometerStft(
            samplesUt = calm,
            sampleRateHz = sampleRate,
            sweepSpeedMps = 0.4,
            currentDeltaUt = 0.0,
            baselineStdUt = 0.5
        )
        assertEquals(0.0, calmStft.apparentMaxDepthM, 1e-6)
    }

    @Test
    fun thermalStyleSurfaceFilter_producesRealContrastAndRotationAlignment() {
        val w = 64
        val h = 48
        val y = ByteArray(w * h) { 105.toByte() }
        val u = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val v = ByteArray((w / 2) * (h / 2)) { 128.toByte() }

        // Inject localized bright oxide surface patch on bottom-right quadrant
        for (row in h / 2 until h) {
            for (col in w / 2 until w) {
                y[row * w + col] = 225.toByte()
            }
        }
        for (row in h / 4 until h / 2) {
            for (col in w / 4 until w / 2) {
                u[row * (w / 2) + col] = 85.toByte()
                v[row * (w / 2) + col] = 205.toByte()
            }
        }

        val ironbow = com.example.domain.FieldReceiverMath.computeThermalStyleSurfacePixels(
            yBytes = y,
            uBytes = u,
            vBytes = v,
            srcWidth = w,
            srcHeight = h,
            yRowStride = w,
            uvRowStride = w / 2,
            uvPixelStride = 1,
            rotationDegrees = 90,
            mode = 1,
            targetLongSide = 64
        )
        // Portrait 90-deg rotation swaps dimensions (width < height)
        assertTrue(ironbow.width < ironbow.height)
        assertEquals(ironbow.width * ironbow.height, ironbow.argbPixels.size)
        assertTrue(ironbow.peakSurfaceContrastZ > 1.2f)
        assertTrue(ironbow.hotspotCoveragePct > 5f)
        // Ensure pixels are distinct (real anomaly palette variation, not uniform solid frame)
        assertTrue(ironbow.argbPixels.toSet().size >= 3)

        // Uneven shadow gradient across frame with a small localized anomaly patch at center
        val shadowY = ByteArray(w * h) { idx ->
            val col = idx % w
            (70 + (col * 110) / w).coerceIn(0, 255).toByte()
        }
        val shadowU = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val shadowV = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        for (row in (h / 2 - 4)..(h / 2 + 4)) {
            for (col in (w / 2 - 4)..(w / 2 + 4)) {
                shadowY[row * w + col] = 240.toByte()
            }
        }
        val lowGain = com.example.domain.FieldReceiverMath.computeThermalStyleSurfacePixels(
            yBytes = shadowY, uBytes = shadowU, vBytes = shadowV,
            srcWidth = w, srcHeight = h, yRowStride = w, uvRowStride = w / 2, uvPixelStride = 1,
            rotationDegrees = 0, mode = 1, targetLongSide = 64, gain = 0.9f
        )
        val highGain = com.example.domain.FieldReceiverMath.computeThermalStyleSurfacePixels(
            yBytes = shadowY, uBytes = shadowU, vBytes = shadowV,
            srcWidth = w, srcHeight = h, yRowStride = w, uvRowStride = w / 2, uvPixelStride = 1,
            rotationDegrees = 0, mode = 1, targetLongSide = 64, gain = 2.8f
        )
        assertTrue(lowGain.peakSurfaceContrastZ > 1.5f)
        val midIdx = (highGain.height / 2) * highGain.width + (highGain.width / 2)
        val cornerIdx = 2 * highGain.width + 2
        val midRedHigh = (highGain.argbPixels[midIdx] shr 16) and 0xFF
        val cornerRedHigh = (highGain.argbPixels[cornerIdx] shr 16) and 0xFF
        assertTrue(midRedHigh > cornerRedHigh + 40)
    }

    @Test
    fun saroojModeAndIndoorClutterRejection_workAccurately() {
        val w = 64
        val h = 48
        // 1. Uniform clay earth with a single localized pale desaturated lime/sarooj patch at center
        val ySarooj = ByteArray(w * h) { 90.toByte() }
        val uSarooj = ByteArray((w / 2) * (h / 2)) { 112.toByte() }
        val vSarooj = ByteArray((w / 2) * (h / 2)) { 155.toByte() } // warm reddish clay
        for (row in 18..30) {
            for (col in 24..38) {
                ySarooj[row * w + col] = 225.toByte() // bleached pale mortar
            }
        }
        for (row in 9..15) {
            for (col in 12..19) {
                uSarooj[row * (w / 2) + col] = 128.toByte() // neutral desaturated gray/lime
                vSarooj[row * (w / 2) + col] = 128.toByte()
            }
        }
        val saroojRes = com.example.domain.FieldReceiverMath.computeThermalStyleSurfacePixels(
            yBytes = ySarooj, uBytes = uSarooj, vBytes = vSarooj,
            srcWidth = w, srcHeight = h, yRowStride = w, uvRowStride = w / 2, uvPixelStride = 1,
            rotationDegrees = 0, mode = 4, targetLongSide = 64
        )
        assertTrue(saroojRes.saroojMatchPct in 3f..20f)
        assertTrue(saroojRes.isLocalizedOpticalAnomaly)

        // 2. Indoor cluttered checkerboard room (high contrast scattered across >40% of frame) -> rejected
        val yClutter = ByteArray(w * h) { idx ->
            val r = idx / w
            val c = idx % w
            if (((r / 4) + (c / 4)) % 2 == 0) 220.toByte() else 55.toByte()
        }
        val uFlat = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val vFlat = ByteArray((w / 2) * (h / 2)) { 128.toByte() }
        val clutterRes = com.example.domain.FieldReceiverMath.computeThermalStyleSurfacePixels(
            yBytes = yClutter, uBytes = uFlat, vBytes = vFlat,
            srcWidth = w, srcHeight = h, yRowStride = w, uvRowStride = w / 2, uvPixelStride = 1,
            rotationDegrees = 0, mode = 1, targetLongSide = 64
        )
        assertTrue(!clutterRes.isLocalizedOpticalAnomaly)
    }

    @Test
    fun antiFakeMagnetometerAlertAndRamGuard_behaveCorrectly() {
        // Indoor rebar / speaker field (>85 uT) flagged as indoor interference
        val indoorCheck = com.example.domain.FieldReceiverMath.evaluateMagnetometerCalibrationWindow(
            samplesUt = List(25) { 88.5 + (it % 2) * 3.0 },
            userThresholdUt = 8f
        )
        assertTrue(indoorCheck.isIndoorInterferenceSuspected)
        assertTrue(indoorCheck.warningReason != null)
        assertTrue(indoorCheck.effectiveFloorUt >= 14.0)

        // Natural calm outdoor field (~46 uT)
        val outdoorCheck = com.example.domain.FieldReceiverMath.evaluateMagnetometerCalibrationWindow(
            samplesUt = List(25) { 46.2 },
            userThresholdUt = 8f
        )
        assertTrue(!outdoorCheck.isIndoorInterferenceSuspected)

        // Alert verdict level 3 for dual lock
        val dualVerdict = com.example.domain.FieldReceiverMath.evaluateDetectionAlert(
            isMagBaselineCalibrated = true,
            isMagConfirmed = true,
            isOpticalConfirmed = true,
            isCameraActive = true,
            magDeltaUt = 19.5,
            opticalMeta = null,
            indoorWarning = null
        )
        assertEquals(3, dualVerdict.level)

        // Uncalibrated never triggers level > 0
        val uncalib = com.example.domain.FieldReceiverMath.evaluateDetectionAlert(
            isMagBaselineCalibrated = false,
            isMagConfirmed = false,
            isOpticalConfirmed = false,
            isCameraActive = false,
            magDeltaUt = 25.0,
            opticalMeta = null,
            indoorWarning = null
        )
        assertEquals(0, uncalib.level)

        // RAM metrics and 90% auto-clean gate
        val (pct91, usedMb, maxMb) = com.example.domain.FieldReceiverMath.computeRamUsageMetrics(
            usedBytes = 91L * 1024L * 1024L,
            maxBytes = 100L * 1024L * 1024L
        )
        assertEquals(91, pct91)
        assertEquals(91, usedMb)
        assertEquals(100, maxMb)
        assertTrue(com.example.domain.FieldReceiverMath.shouldAutoCleanRam(pct91, 90))
        assertTrue(!com.example.domain.FieldReceiverMath.shouldAutoCleanRam(85, 90))
    }

    @Test
    fun earthSearchFallbackCollectionMapping_mapsRtcToGrd() {
        val mapped = com.example.data.RadarRepository.mapCollectionsForEarthSearch(
            listOf("sentinel-1-rtc", "sentinel-2-l2a", "cop-dem-glo-30", "landsat-c2-l2")
        )
        assertEquals(
            listOf("sentinel-1-grd", "sentinel-2-l2a", "cop-dem-glo-30", "landsat-c2-l2"),
            mapped
        )
    }

    @Test
    fun adaptiveDepthFilter_suppressesShallowSpikeAndBoostsWideDeepHalo() {
        val baseline = 45.0
        // 1. Sharp narrow single-sample surface iron clutter spike on calm baseline
        val spikeSamples = MutableList(32) { baseline }
        spikeSamples[31] = baseline + 18.0
        val stftSpike = com.example.domain.FieldReceiverMath.computeMagnetometerStft(
            samplesUt = spikeSamples,
            sampleRateHz = 25.0,
            sweepSpeedMps = 0.40,
            currentDeltaUt = 18.0,
            baselineStdUt = 0.5
        )
        val spikeFilter = com.example.domain.MaterialSignatureEngine.computeAdaptiveDepthFilter(
            samplesUt = spikeSamples,
            baselineMeanUt = baseline,
            baselineStdUt = 0.5,
            stft = stftSpike,
            opticalMeta = null,
            userBaseThresholdUt = 8f,
            depthBandMode = 1 // Deep mode
        )
        assertTrue(spikeFilter.surfaceClutterSuppressed)
        assertTrue(abs(spikeFilter.deepEnvelopeUt) < 6.0)

        // 2. Broad smooth low-frequency deep anomaly (-4.5 uT sustained envelope)
        val deepHalo = List(40) { idx ->
            val phase = idx / 40.0
            baseline - 4.5 * kotlin.math.sin(kotlin.math.PI * phase * 0.5 + kotlin.math.PI * 0.25)
        }
        val stftDeep = com.example.domain.FieldReceiverMath.computeMagnetometerStft(
            samplesUt = deepHalo,
            sampleRateHz = 25.0,
            sweepSpeedMps = 0.40,
            currentDeltaUt = deepHalo.last() - baseline,
            baselineStdUt = 0.4
        )
        val deepFilter = com.example.domain.MaterialSignatureEngine.computeAdaptiveDepthFilter(
            samplesUt = deepHalo,
            baselineMeanUt = baseline,
            baselineStdUt = 0.4,
            stft = stftDeep,
            opticalMeta = null,
            userBaseThresholdUt = 10f,
            depthBandMode = 0 // Auto Adaptive
        )
        assertTrue(!deepFilter.surfaceClutterSuppressed)
        assertTrue(deepFilter.adaptiveThresholdUt < 10.0)
        assertTrue(deepFilter.soilFootprintWidthM > 0.5)
    }

    @Test
    fun materialSignatureAnalysis_distinguishesNonFerrousLowBandFromFerrousSpike() {
        val baseline = 45.0
        // Smooth negative diamagnetic trough -> non-ferrous/diamagnetic profile ranks above ferrous iron
        val diamagSamples = List(40) { idx ->
            baseline - 3.8 * kotlin.math.sin((idx / 39.0) * kotlin.math.PI * 0.65)
        }
        val stftNonFerrous = com.example.domain.FieldReceiverMath.computeMagnetometerStft(
            samplesUt = diamagSamples,
            sampleRateHz = 25.0,
            sweepSpeedMps = 0.40,
            currentDeltaUt = diamagSamples.last() - baseline,
            baselineStdUt = 0.4
        )
        val depthRes = com.example.domain.MaterialSignatureEngine.computeAdaptiveDepthFilter(
            samplesUt = diamagSamples,
            baselineMeanUt = baseline,
            baselineStdUt = 0.4,
            stft = stftNonFerrous,
            opticalMeta = null,
            userBaseThresholdUt = 8f,
            depthBandMode = 0
        )
        val sigNonFerrous = com.example.domain.MaterialSignatureEngine.analyzeLiveMaterialSignature(
            stft = stftNonFerrous,
            depthFilter = depthRes,
            baselineStdUt = 0.4
        )
        assertTrue(!sigNonFerrous.isFerrousOrNoiseDominant)
        assertTrue(sigNonFerrous.rankedMatches.first().id in listOf("gold", "silver", "copper"))

        // High positive spike with clutter -> ferrous iron dominates
        val ironSamples = MutableList(32) { baseline }
        for (i in 24..31) ironSamples[i] = baseline + 22.0 + if (i % 2 == 0) 4.0 else -3.5
        val stftIron = com.example.domain.FieldReceiverMath.computeMagnetometerStft(
            samplesUt = ironSamples,
            sampleRateHz = 25.0,
            sweepSpeedMps = 0.40,
            currentDeltaUt = 22.0,
            baselineStdUt = 0.6
        )
        val ironDepth = com.example.domain.MaterialSignatureEngine.computeAdaptiveDepthFilter(
            samplesUt = ironSamples,
            baselineMeanUt = baseline,
            baselineStdUt = 0.6,
            stft = stftIron,
            opticalMeta = null,
            userBaseThresholdUt = 8f,
            depthBandMode = 3
        )
        val sigIron = com.example.domain.MaterialSignatureEngine.analyzeLiveMaterialSignature(
            stft = stftIron,
            depthFilter = ironDepth,
            baselineStdUt = 0.6
        )
        assertTrue(sigIron.isFerrousOrNoiseDominant)
    }

    @Test
    fun satelliteMultiMaterialSignatures_identifiesHydrothermalQuartzClayHost() {
        val dim = 15
        val total = dim * dim
        val centerLat = 35.17
        val centerLon = 49.66
        val dDeg = 0.3 / 111.0
        val dem = FloatArray(total) { 1500f }
        // Inject exact hydrothermal quartz-clay host reflectance profile [B02..B12]
        val blue = FloatArray(total) { 0.14f }
        val green = FloatArray(total) { 0.18f }
        val red = FloatArray(total) { 0.23f }
        val nir = FloatArray(total) { 0.28f }
        val swir1 = FloatArray(total) { 0.42f }
        val swir2 = FloatArray(total) { 0.21f }
        val scl = IntArray(total) { 5 }

        val res = com.example.domain.SarOpticalFusionEngine.runPipeline(
            width = dim,
            height = dim,
            minLon = centerLon - dDeg,
            minLat = centerLat - dDeg,
            maxLon = centerLon + dDeg,
            maxLat = centerLat + dDeg,
            centerLat = centerLat,
            centerLon = centerLon,
            demElevations = dem,
            sarAscendingVv = null,
            sarAscendingVh = null,
            sarDescendingVv = null,
            sarDescendingVh = null,
            s2SummerBlue = blue,
            s2SummerGreen = green,
            s2SummerRed = red,
            s2SummerNir = nir,
            s2SummerSwir1 = swir1,
            s2SummerSwir2 = swir2,
            s2SummerScl = scl
        )
        val mat = res.materialSignatures!!
        assertTrue(mat.dominantMaterialLabel.contains("طلا/مس"))
        assertTrue((mat.hydrothermalGoldCopperHostSamDeg ?: 90.0) < 2.0)
    }

    @Test
    fun dedicatedDepthFilterAndSpectralProcessorClasses_processLiveStreamDirectly() {
        val depthFilter = com.example.domain.AdaptiveDepthFilter()
        val spectralProcessor = com.example.domain.MaterialSpectralSignatureProcessor()
        assertEquals(5, spectralProcessor.getReferenceModels().size)

        val baseline = 46.0
        val stream = List(40) { idx ->
            baseline - 4.2 * kotlin.math.sin((idx / 39.0) * kotlin.math.PI * 0.60)
        }
        val noiseEst = depthFilter.computeSurfaceSoilNoise(
            samplesUt = stream,
            baselineMeanUt = baseline,
            stft = null,
            opticalMeta = null
        )
        assertTrue(noiseEst.shallowClutterUt < 0.6)
        assertTrue(noiseEst.deepEnvelopeUt < -1.5)

        val isolated = depthFilter.filterAndIsolateDeepSignal(
            samplesUt = stream,
            baselineMeanUt = baseline,
            baselineStdUt = 0.4,
            stft = null,
            opticalMeta = null,
            userBaseThresholdUt = 8f,
            depthBandMode = 1
        )
        assertTrue(!isolated.surfaceClutterSuppressed)
        assertTrue(isolated.adaptiveThresholdUt < 8.0)

        val analysis = spectralProcessor.processRawMagnetometerStream(
            samplesUt = stream,
            baselineMeanUt = baseline,
            baselineStdUt = 0.4,
            depthFilter = depthFilter
        )
        assertTrue(!analysis.isFerrousOrNoiseDominant)
        assertTrue(analysis.rankedMatches.any { it.id == "gold" })
        assertTrue(analysis.rankedMatches.any { it.id == "silver" })
        assertTrue(analysis.rankedMatches.any { it.id == "copper" })
    }

    @Test
    fun fieldCompanionWatchdog_silentUnlessPermittedAndDetectsOvershoot() {
        val watchdog = com.example.domain.FieldCompanionWatchdog(alertCooldownMs = 10_000L)

        // 1. Even if user walks past target (3.0m -> 6.5m), watchdog MUST stay silent when enabled=false
        assertNull(watchdog.evaluateStep(1_000L, enabled = false, distanceToTargetM = 3.0, isMagCalibrated = true, depthFilter = null, materialSig = null))
        assertNull(watchdog.evaluateStep(2_000L, enabled = false, distanceToTargetM = 6.5, isMagCalibrated = true, depthFilter = null, materialSig = null))

        // 2. When enabled=true: approaching 3.0m then overshooting to 6.5m triggers TARGET_OVERSHOOT
        watchdog.reset()
        assertNull(watchdog.evaluateStep(1_000L, enabled = true, distanceToTargetM = 3.0, isMagCalibrated = true, depthFilter = null, materialSig = null))
        val overshoot = watchdog.evaluateStep(2_000L, enabled = true, distanceToTargetM = 6.5, isMagCalibrated = true, depthFilter = null, materialSig = null)
        assertNotNull(overshoot)
        assertEquals(com.example.domain.WatchdogAlertType.TARGET_OVERSHOOT, overshoot?.alertType)

        // 3. Cooldown prevents nagging right away
        val suppressedDuringCooldown = watchdog.evaluateStep(5_000L, enabled = true, distanceToTargetM = 8.5, isMagCalibrated = true, depthFilter = null, materialSig = null)
        assertNull(suppressedDuringCooldown)

        // 4. Deep anomaly envelope peak (9.5 uT) followed by sharp drop (2.0 uT) after cooldown triggers DEEP_ANOMALY_OVERSHOOT
        val peakFilter = com.example.domain.AdaptiveDepthFilterResult(
            rawDeltaUt = -9.5,
            deepEnvelopeUt = -9.5,
            shallowClutterUt = 0.4,
            soilFootprintWidthM = 1.4,
            adaptiveThresholdUt = 6.0,
            sensitivityGainFactor = 1.3,
            surfaceClutterSuppressed = false,
            filterModeLabel = "Auto",
            activeSignalUt = -9.5
        )
        val dropFilter = peakFilter.copy(rawDeltaUt = -2.0, deepEnvelopeUt = -2.0, activeSignalUt = -2.0)
        assertNull(watchdog.evaluateStep(15_000L, enabled = true, distanceToTargetM = null, isMagCalibrated = true, depthFilter = peakFilter, materialSig = null))
        val anomalyOvershoot = watchdog.evaluateStep(16_000L, enabled = true, distanceToTargetM = null, isMagCalibrated = true, depthFilter = dropFilter, materialSig = null)
        assertNotNull(anomalyOvershoot)
        assertEquals(com.example.domain.WatchdogAlertType.DEEP_ANOMALY_OVERSHOOT, anomalyOvershoot?.alertType)
    }

    @Test
    fun atmosphericCorrection_saastamoinenModelIsPhysicallyRealistic() {
        val corr = RadarMathEngine.computeAtmosphericCorrection(
            pressureHpa = 1013.25,
            temperatureC = 20.0,
            relativeHumidityPct = 50.0,
            soilMoistureM3 = 0.22,
            centerLat = 35.17,
            meanElevationM = 1200.0
        )
        assertTrue(corr.zenithHydrostaticDelayM in 2.0..2.6)
        assertTrue(corr.zenithWetDelayM in 0.08..0.22)
        assertTrue(corr.refractivityN > 250.0)
    }

    @Test
    fun geodeticOffset_computesExactMetersAndAzimuth() {
        // Tehran to ~1km North
        val lat1 = 35.0
        val lon1 = 51.0
        val lat2 = 35.009044 // ~1000m north
        val lon2 = 51.0
        val offset = RadarMathEngine.computeGeodeticOffset(lat1, lon1, lat2, lon2, 0.0, 0.0)
        assertTrue(offset.distanceMeters in 990.0..1015.0)
        assertTrue(abs(offset.forwardAzimuthDeg - 0.0) < 1.5)
    }

    @Test
    fun magneticKalmanFilter_stabilizesSensorJitterAndPreservesSweepTrend() {
        val kalman = com.example.sensors.MagneticKalmanFilter()

        // 1. Steady baseline with high-frequency sensor noise (+1.5, -1.2, +1.8, -1.5)
        val steadyJitter = listOf(45.0, 46.5, 43.8, 46.8, 43.5, 46.2, 43.9, 46.0, 44.1, 45.8)
        val filteredSteady = steadyJitter.map { raw ->
            kalman.update(
                rawBx = raw,
                rawBy = 0.0,
                rawBz = 0.0,
                gyroRateRadS = 0.02,
                linearAccelMagMps2 = 0.05
            ).bxUt
        }
        // Last filtered reading should be very close to true mean 45.0 uT despite alternating +/- 1.8 uT noise
        assertTrue(abs(filteredSteady.last() - 45.0) < 0.8)

        // 2. Fast sweep over target (+12.0 uT) with active gyro motion
        val stepTarget = kalman.update(
            rawBx = 57.0,
            rawBy = 0.0,
            rawBz = 0.0,
            gyroRateRadS = 1.4,
            linearAccelMagMps2 = 1.2
        )
        // With high gyro rate, Kalman gain expands dynamically so target step is tracked immediately
        assertTrue(stepTarget.bxUt > 52.0)
    }
}
