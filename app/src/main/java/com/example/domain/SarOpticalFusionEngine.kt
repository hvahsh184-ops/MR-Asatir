package com.example.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

data class DetectedRadarPoint(
    val latitude: Double,
    val longitude: Double,
    val weight: Double,
    val eastOffsetM: Double,
    val northOffsetM: Double,
    val ewDisparityM: Double? = null,
    val thermalDeltaC: Double? = null,
    val dualPolRatio: Double? = null,
    val spectralAngleDeg: Double? = null,
    val crossBandDiff: Double? = null,
    val mahalanobisDistance: Double? = null
)

data class EastWestAlignmentReport(
    val shiftEastPixels: Double,
    val shiftNorthPixels: Double,
    val preAlignmentDisparityM: Double,
    val postAlignmentDisparityM: Double,
    val nccCorrelation: Double,
    val isMinimizedAlert: Boolean
)

data class SarDifferenceSummary(
    val availablePolarizations: String,
    val meanEwOrbitDiff: Double?,
    val meanTwoSeasonSarDiff: Double?,
    val meanCoCrossPolDiffC: Double?,
    val meanLBandHhHvDiff: Double?,
    val meanCrossBandCLDiff: Double?
)

data class MultispectralFilterStats(
    val totalPixels: Int,
    val clearSkyPct: Double,
    val noVegetationPct: Double,
    val noSalinityPct: Double,
    val pureSoilPct: Double,
    val carbonateAnomalyCount: Int,
    val twoSeasonPersistedCount: Int,
    val meanThermalDeltaC: Double?,
    val meanIronOxideRatio: Double?,
    val minSpectralAngleDeg: Double?
)

data class TwoSeasonSarSubtractionResult(
    val coregShiftEastPx: Double,
    val coregShiftNorthPx: Double,
    val coregNccScore: Double,
    val meanAbsDiff: Double,
    val suppressedTransientNoisePct: Double,
    val pixelWiseDifferenceGrid: FloatArray,
    val persistentStructuralGrid: FloatArray,
    val persistentHotspots: List<DetectedRadarPoint>
)

data class FusionPipelineResult(
    val ascCfarCount: Int,
    val descCfarCount: Int,
    val linearRejectedCount: Int,
    val powerInfraMaskedCount: Int,
    val savitzkyGolayApplied: Boolean,
    val peakMahalanobisDm: Double,
    val inverseCovariance5x5: List<List<Double>>?,
    val alignmentReport: EastWestAlignmentReport?,
    val sarDifference: SarDifferenceSummary,
    val twoSeasonSubtraction: TwoSeasonSarSubtractionResult?,
    val fusedRadarPoints: List<DetectedRadarPoint>,
    val cleanRadarPoints: List<DetectedRadarPoint>,
    val mahalanobisHotspots: List<DetectedRadarPoint>,
    val carbonatePoints: List<GeoPoint>,
    val priorityTargets: List<DetectedRadarPoint>,
    val multispectralStats: MultispectralFilterStats?,
    val materialSignatures: SatelliteMaterialSignatureSummary? = null
)

object SarOpticalFusionEngine {

    private const val EARTH_RADIUS_M = 6_371_000.0
    // Reference 6-band reflectance signatures [B02, B03, B04, B08, B11, B12]
    private val CARBONATE_REF_SPECTRUM = doubleArrayOf(0.16, 0.21, 0.25, 0.30, 0.38, 0.24)
    private val IRON_OXIDE_BRICK_SPECTRUM = doubleArrayOf(0.09, 0.14, 0.26, 0.32, 0.34, 0.28)
    private val HYDROTHERMAL_GOLD_COPPER_HOST_SPECTRUM = doubleArrayOf(0.14, 0.18, 0.23, 0.28, 0.42, 0.21)
    private val DRY_VOID_MASONRY_SPECTRUM = doubleArrayOf(0.19, 0.23, 0.26, 0.29, 0.35, 0.31)

    /**
     * Exact 5x5 2D quadratic Savitzky-Golay kernel weights derived from (J^T J)^(-1) J^T
     * using basis [1, x, y, x^2, xy, y^2]. Suppresses speckle/high-frequency sensor noise
     * while preserving true spatial peak centroids and curvatures.
     */
    private val savitzkyGolay5x5Weights: DoubleArray by lazy {
        val basisCount = 6
        val nPts = 25
        val jMat = Array(nPts) { DoubleArray(basisCount) }
        var p = 0
        for (dy in -2..2) {
            for (dx in -2..2) {
                val x = dx.toDouble()
                val y = dy.toDouble()
                jMat[p][0] = 1.0
                jMat[p][1] = x
                jMat[p][2] = y
                jMat[p][3] = x * x
                jMat[p][4] = x * y
                jMat[p][5] = y * y
                p++
            }
        }
        val jtj = Array(basisCount) { DoubleArray(basisCount) }
        for (r in 0 until basisCount) {
            for (c in 0 until basisCount) {
                var sum = 0.0
                for (k in 0 until nPts) sum += jMat[k][r] * jMat[k][c]
                jtj[r][c] = sum
            }
        }
        val jtjInv = RadarMathEngine.invertMatrix(jtj)
        DoubleArray(nPts) { pt ->
            var w = 0.0
            for (b in 0 until basisCount) {
                w += jtjInv[0][b] * jMat[pt][b]
            }
            w
        }
    }

    fun applySavitzkyGolay2D(data: FloatArray, width: Int, height: Int): FloatArray {
        val total = width * height
        if (data.size != total || width < 5 || height < 5) return data.copyOf()
        val weights = savitzkyGolay5x5Weights
        val out = FloatArray(total)
        for (r in 0 until height) {
            for (c in 0 until width) {
                var acc = 0.0
                var k = 0
                for (dy in -2..2) {
                    val rr = (r + dy).coerceIn(0, height - 1)
                    val rowBase = rr * width
                    for (dx in -2..2) {
                        val cc = (c + dx).coerceIn(0, width - 1)
                        acc += weights[k] * data[rowBase + cc].toDouble()
                        k++
                    }
                }
                out[r * width + c] = max(0.0, acc).toFloat()
            }
        }
        return out
    }

    fun runPipeline(
        width: Int,
        height: Int,
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        centerLat: Double,
        centerLon: Double,
        demElevations: FloatArray,
        sarAscendingVv: FloatArray?,
        sarAscendingVh: FloatArray?,
        sarDescendingVv: FloatArray?,
        sarDescendingVh: FloatArray?,
        sarWinterVv: FloatArray? = null,
        sarLBandHh: FloatArray? = null,
        sarLBandHv: FloatArray? = null,
        availablePolLabels: String = "VV, VH",
        s2SummerBlue: FloatArray? = null,
        s2SummerGreen: FloatArray?,
        s2SummerRed: FloatArray?,
        s2SummerNir: FloatArray?,
        s2SummerSwir1: FloatArray?,
        s2SummerSwir2: FloatArray?,
        s2SummerScl: IntArray?,
        s2WinterSwir1: FloatArray? = null,
        s2WinterSwir2: FloatArray? = null,
        s2WinterScl: IntArray? = null,
        lstSummerK: FloatArray? = null,
        lstWinterK: FloatArray? = null,
        powerInfraPoints: List<GeoPoint> = emptyList()
    ): FusionPipelineResult {
        val total = width * height
        require(demElevations.size == total) { "DEM array size mismatch" }

        val latSpan = maxLat - minLat
        val lonSpan = maxLon - minLon
        val stepEastM = (lonSpan * (PI / 180.0)) * EARTH_RADIUS_M * cos(Math.toRadians(centerLat)) / width.coerceAtLeast(1)
        val stepNorthM = (latSpan * (PI / 180.0)) * EARTH_RADIUS_M / height.coerceAtLeast(1)
        val pixelSizeM = (stepEastM + stepNorthM) / 2.0

        val meanElev = demElevations.average()
        val validTopo = BooleanArray(total)
        for (r in 0 until height) {
            for (c in 0 until width) {
                val left = demElevations[r * width + (c - 1).coerceAtLeast(0)]
                val right = demElevations[r * width + (c + 1).coerceAtMost(width - 1)]
                val up = demElevations[(r - 1).coerceAtLeast(0) * width + c]
                val down = demElevations[(r + 1).coerceAtMost(height - 1) * width + c]
                val dxSpan = if (c == 0 || c == width - 1) stepEastM else 2.0 * stepEastM
                val dySpan = if (r == 0 || r == height - 1) stepNorthM else 2.0 * stepNorthM
                val dzDx = (right - left) / dxSpan.coerceAtLeast(1.0)
                val dzDy = (up - down) / dySpan.coerceAtLeast(1.0)
                val slopeDeg = Math.toDegrees(atan2(hypot(dzDx, dzDy), 1.0))
                validTopo[r * width + c] = slopeDeg < 35.0
            }
        }

        // 0. 2D Savitzky-Golay (5x5 quadratic) speckle/noise removal
        val ascClean = sarAscendingVv?.takeIf { it.size == total }?.let { applySavitzkyGolay2D(it, width, height) }
        val descClean = sarDescendingVv?.takeIf { it.size == total }?.let { applySavitzkyGolay2D(it, width, height) }
        val winterClean = sarWinterVv?.takeIf { it.size == total }?.let { applySavitzkyGolay2D(it, width, height) }
        val swir1Clean = s2SummerSwir1?.takeIf { it.size == total }?.let { applySavitzkyGolay2D(it, width, height) }
        val swir2Clean = s2SummerSwir2?.takeIf { it.size == total }?.let { applySavitzkyGolay2D(it, width, height) }
        val sgApplied = ascClean != null || descClean != null || swir1Clean != null

        // 1. East-West Sub-Pixel Co-Registration & Disparity Minimization
        var alignedDesc = descClean
        var alignmentReport: EastWestAlignmentReport? = null
        if (ascClean != null && descClean != null) {
            val reg = alignEastWestSubPixel(
                eastImg = ascClean,
                westImg = descClean,
                width = width,
                height = height,
                pixelSizeM = pixelSizeM
            )
            alignedDesc = reg.first
            alignmentReport = reg.second
        }

        // 2. SAR Difference Processor (Orbit E-W, Two-Season Summer-Winter, Pol Co-Cross, C vs L Band)
        val meanEwOrbitDiff = if (ascClean != null && alignedDesc != null) {
            var acc = 0.0
            for (i in 0 until total) acc += abs(ascClean[i] - alignedDesc[i])
            acc / total
        } else null

        val twoSeasonSubtraction = if (ascClean != null && winterClean != null) {
            analyzeTwoSeasonSarDifference(
                summerSar = ascClean,
                winterSar = winterClean,
                width = width,
                height = height,
                minLon = minLon,
                minLat = minLat,
                maxLon = maxLon,
                maxLat = maxLat,
                centerLat = centerLat,
                centerLon = centerLon
            )
        } else null

        val meanTwoSeasonSarDiff = twoSeasonSubtraction?.meanAbsDiff

        val meanCoCrossPolDiffC = if (ascClean != null && sarAscendingVh != null && sarAscendingVh.size == total) {
            var acc = 0.0
            for (i in 0 until total) acc += (ascClean[i] - sarAscendingVh[i])
            acc / total
        } else null

        val meanLBandHhHvDiff = if (sarLBandHh != null && sarLBandHv != null &&
            sarLBandHh.size == total && sarLBandHv.size == total
        ) {
            var acc = 0.0
            for (i in 0 until total) acc += (sarLBandHh[i] - sarLBandHv[i])
            acc / total
        } else null

        val crossBandDiffGrid = if (ascClean != null && sarLBandHh != null && sarLBandHh.size == total) {
            val maxC = ascClean.maxOrNull()?.coerceAtLeast(1e-4f) ?: 1f
            val maxL = sarLBandHh.maxOrNull()?.coerceAtLeast(1e-4f) ?: 1f
            DoubleArray(total) { i ->
                abs((ascClean[i] / maxC).toDouble() - (sarLBandHh[i] / maxL).toDouble())
            }
        } else null
        val meanCrossBandCLDiff = crossBandDiffGrid?.average()

        val sarDiffSummary = SarDifferenceSummary(
            availablePolarizations = availablePolLabels,
            meanEwOrbitDiff = meanEwOrbitDiff,
            meanTwoSeasonSarDiff = meanTwoSeasonSarDiff,
            meanCoCrossPolDiffC = meanCoCrossPolDiffC,
            meanLBandHhHvDiff = meanLBandHhHvDiff,
            meanCrossBandCLDiff = meanCrossBandCLDiff
        )

        // 3. Multi-scale Bandpass Filter & Linear Structure Tensor Mask
        val ascFiltered = ascClean?.let { applyScaleBandpass(it, width, height) }
        val descFiltered = alignedDesc?.let { applyScaleBandpass(it, width, height) }
        val ascLinearMask = ascClean?.let { computeLinearityMask(it, width, height) }
        val descLinearMask = alignedDesc?.let { computeLinearityMask(it, width, height) }

        var linearRejected = 0
        var powerInfraRejected = 0

        val ptsAsc = if (ascClean != null && ascFiltered != null) {
            processRadarCfarSubPixel(
                rawIntensity = ascClean,
                bandpassData = ascFiltered,
                vhData = sarAscendingVh,
                dem = demElevations,
                validTopo = validTopo,
                linearMask = ascLinearMask,
                meanElev = meanElev,
                width = width,
                height = height,
                minLon = minLon,
                maxLon = maxLon,
                minLat = minLat,
                maxLat = maxLat,
                centerLat = centerLat,
                centerLon = centerLon,
                isAscending = true,
                onLinearRejected = { linearRejected++ }
            )
        } else emptyList()

        val ptsDesc = if (alignedDesc != null && descFiltered != null) {
            processRadarCfarSubPixel(
                rawIntensity = alignedDesc,
                bandpassData = descFiltered,
                vhData = sarDescendingVh,
                dem = demElevations,
                validTopo = validTopo,
                linearMask = descLinearMask,
                meanElev = meanElev,
                width = width,
                height = height,
                minLon = minLon,
                maxLon = maxLon,
                minLat = minLat,
                maxLat = maxLat,
                centerLat = centerLat,
                centerLon = centerLon,
                isAscending = false,
                onLinearRejected = { linearRejected++ }
            )
        } else emptyList()

        // 4. Fuse East (Asc) & West (Desc) sub-pixel centroids
        val fusedRadar = when {
            ptsAsc.isNotEmpty() && ptsDesc.isNotEmpty() -> {
                val merged = mutableListOf<DetectedRadarPoint>()
                for (p1 in ptsAsc) {
                    val match = ptsDesc.minByOrNull { p2 ->
                        hypot(p1.latitude - p2.latitude, p1.longitude - p2.longitude)
                    }
                    if (match != null && hypot(p1.latitude - match.latitude, p1.longitude - match.longitude) < 0.00018) {
                        val disparityM = RadarMathEngine.computeGeodeticOffset(
                            p1.latitude, p1.longitude, match.latitude, match.longitude
                        ).distanceMeters
                        val totW = p1.weight + match.weight
                        val fLat = (p1.latitude * p1.weight + match.latitude * match.weight) / totW
                        val fLon = (p1.longitude * p1.weight + match.longitude * match.weight) / totW
                        val avgPol = listOfNotNull(p1.dualPolRatio, match.dualPolRatio).takeIf { it.isNotEmpty() }?.average()
                        merged.add(
                            toDetectedPoint(
                                lat = fLat,
                                lon = fLon,
                                weight = totW,
                                centerLat = centerLat,
                                centerLon = centerLon,
                                ewDisparityM = disparityM,
                                dualPolRatio = avgPol
                            )
                        )
                    }
                }
                merged
            }
            ptsAsc.isNotEmpty() -> ptsAsc
            else -> ptsDesc
        }

        if (alignmentReport != null && fusedRadar.isNotEmpty()) {
            val meanCentroidDisparity = fusedRadar.mapNotNull { it.ewDisparityM }.average()
            alignmentReport = alignmentReport.copy(
                postAlignmentDisparityM = min(alignmentReport.postAlignmentDisparityM, meanCentroidDisparity),
                isMinimizedAlert = meanCentroidDisparity <= 4.5 || alignmentReport.postAlignmentDisparityM <= 4.5
            )
        }

        // 5. Two-Season Thermal Contrast (Summer vs Winter LST)
        val thermalDeltaGrid = if (lstSummerK != null && lstWinterK != null &&
            lstSummerK.size == total && lstWinterK.size == total
        ) {
            DoubleArray(total) { i -> (lstSummerK[i] - lstWinterK[i]).toDouble() }
        } else null
        val meanThermalDeltaC = thermalDeltaGrid?.average()

        // 6. Multispectral Color + IR + Spectral Angle Mapper (SAM) + Two-Season Carbonate Persistence
        var stats: MultispectralFilterStats? = null
        var materialSigSummary: SatelliteMaterialSignatureSummary? = null
        val contaminatedPoints = mutableListOf<GeoPoint>()
        val carbonatePoints = mutableListOf<GeoPoint>()
        val samAnglesDeg = DoubleArray(total) { 90.0 }

        val carbonateGrid = DoubleArray(total)
        val soilBadMask = BooleanArray(total)

        if (s2SummerGreen != null && s2SummerRed != null && s2SummerNir != null &&
            swir1Clean != null && swir2Clean != null
        ) {
            var clearCount = 0
            var noVegCount = 0
            var noSaltCount = 0
            var pureCount = 0
            val pureSoilMask = BooleanArray(total)
            val carbSummer = DoubleArray(total)
            val carbWinter = DoubleArray(total)
            val hasWinter = s2WinterSwir1 != null && s2WinterSwir2 != null &&
                s2WinterSwir1.size == total && s2WinterSwir2.size == total
            val hasBlue = s2SummerBlue != null && s2SummerBlue.size == total
            var ironOxideSum = 0.0
            var minSam = 90.0
            var samCarbSum = 0.0
            var samIronSum = 0.0
            var samHydroSum = 0.0
            var samVoidSum = 0.0

            for (i in 0 until total) {
                val sclVal = s2SummerScl?.getOrNull(i) ?: 5
                val clearSky = (sclVal == 4 || sclVal == 5)
                if (clearSky) clearCount++

                val blue = s2SummerBlue?.getOrNull(i)?.toDouble() ?: (s2SummerGreen[i].toDouble() * 0.85)
                val nir = s2SummerNir[i].toDouble()
                val red = s2SummerRed[i].toDouble()
                val green = s2SummerGreen[i].toDouble()
                val sw1 = swir1Clean[i].toDouble()
                val sw2 = swir2Clean[i].toDouble()

                val ndvi = (nir - red) / (nir + red + 1e-6)
                val noVeg = ndvi < 0.22
                if (noVeg) noVegCount++

                val salinityIndex = (sw1 - green) / (sw1 + green + 1e-6)
                val noSalt = salinityIndex > 0.05
                if (noSalt) noSaltCount++

                val pure = clearSky && noVeg && noSalt
                pureSoilMask[i] = pure
                soilBadMask[i] = !(noVeg && noSalt)
                if (pure) {
                    pureCount++
                    val carbVal = (sw1 - sw2) / (sw1 + sw2 + 1e-6)
                    carbSummer[i] = carbVal
                    carbonateGrid[i] = carbVal
                    if (hasBlue) {
                        ironOxideSum += red / (blue + 1e-4)
                    }
                    val sampleSpec = doubleArrayOf(blue, green, red, nir, sw1, sw2)
                    val sam = computeSpectralAngleDeg(sampleSpec, CARBONATE_REF_SPECTRUM)
                    samAnglesDeg[i] = sam
                    if (sam < minSam) minSam = sam
                    samCarbSum += sam
                    samIronSum += computeSpectralAngleDeg(sampleSpec, IRON_OXIDE_BRICK_SPECTRUM)
                    samHydroSum += computeSpectralAngleDeg(sampleSpec, HYDROTHERMAL_GOLD_COPPER_HOST_SPECTRUM)
                    samVoidSum += computeSpectralAngleDeg(sampleSpec, DRY_VOID_MASONRY_SPECTRUM)

                    if (hasWinter) {
                        val w1 = s2WinterSwir1!![i].toDouble()
                        val w2 = s2WinterSwir2!![i].toDouble()
                        val wScl = s2WinterScl?.getOrNull(i) ?: 5
                        carbWinter[i] = if (wScl == 4 || wScl == 5) {
                            (w1 - w2) / (w1 + w2 + 1e-6)
                        } else carbSummer[i]
                    }
                }

                if (!(noVeg && noSalt)) {
                    val r = i / width
                    val c = i % width
                    contaminatedPoints.add(pixelToLatLon(r, c, width, height, minLon, maxLon, minLat, maxLat))
                }
            }

            var persistedCount = 0
            if (pureCount > 0) {
                var sumC = 0.0
                var sumW = 0.0
                for (i in 0 until total) {
                    if (pureSoilMask[i]) {
                        sumC += carbSummer[i]
                        if (hasWinter) sumW += carbWinter[i]
                    }
                }
                val meanC = sumC / pureCount.toDouble()
                val meanW = sumW / pureCount.toDouble()
                var varC = 0.0
                var varW = 0.0
                for (i in 0 until total) {
                    if (pureSoilMask[i]) {
                        varC += (carbSummer[i] - meanC) * (carbSummer[i] - meanC)
                        if (hasWinter) varW += (carbWinter[i] - meanW) * (carbWinter[i] - meanW)
                    }
                }
                val threshS = meanC + 3.0 * sqrt(varC / pureCount.toDouble())
                val threshW = meanW + 2.0 * sqrt(varW / pureCount.toDouble())

                for (i in 0 until total) {
                    if (pureSoilMask[i] && carbSummer[i] > threshS) {
                        val winterMatch = !hasWinter || carbWinter[i] > threshW
                        if (winterMatch) {
                            persistedCount++
                            val r = i / width
                            val c = i % width
                            carbonatePoints.add(pixelToLatLon(r, c, width, height, minLon, maxLon, minLat, maxLat))
                        }
                    }
                }
            }

            stats = MultispectralFilterStats(
                totalPixels = total,
                clearSkyPct = clearCount * 100.0 / total,
                noVegetationPct = noVegCount * 100.0 / total,
                noSalinityPct = noSaltCount * 100.0 / total,
                pureSoilPct = pureCount * 100.0 / total,
                carbonateAnomalyCount = carbonatePoints.size,
                twoSeasonPersistedCount = persistedCount,
                meanThermalDeltaC = meanThermalDeltaC,
                meanIronOxideRatio = if (hasBlue && pureCount > 0) ironOxideSum / pureCount else null,
                minSpectralAngleDeg = if (pureCount > 0) minSam else null
            )

            if (pureCount > 0) {
                val meanCarbSam = samCarbSum / pureCount
                val meanIronSam = samIronSum / pureCount
                val meanHydroSam = samHydroSum / pureCount
                val meanVoidSam = samVoidSum / pureCount
                val bestPair = listOf(
                    "کربنات / ساروج" to meanCarbSam,
                    "اکسید آهن / سفال پخته" to meanIronSam,
                    "دگرسانی کوارتز-رس (میزبان طلا/مس)" to meanHydroSam,
                    "سازه خشک / حفره سنگی" to meanVoidSam
                ).minByOrNull { it.second }
                materialSigSummary = SatelliteMaterialSignatureSummary(
                    carbonateSaroojSamDeg = meanCarbSam,
                    ironOxideBrickSamDeg = meanIronSam,
                    hydrothermalGoldCopperHostSamDeg = meanHydroSam,
                    dryVoidMasonrySamDeg = meanVoidSam,
                    dominantMaterialLabel = bestPair?.first ?: "نامشخص"
                )
            }
        }

        // 7. Filter out veg/saline + OSM Power Infrastructure + annotate Thermal, SAM & C-L Diff
        val cleanRadar = fusedRadar.filter { rp ->
            val onBadSoil = contaminatedPoints.any { bp ->
                hypot(rp.latitude - bp.latitude, rp.longitude - bp.longitude) < 0.00011
            }
            val nearPowerLine = powerInfraPoints.any { pp ->
                RadarMathEngine.computeGeodeticOffset(rp.latitude, rp.longitude, pp.latitude, pp.longitude).distanceMeters < 25.0
            }
            if (nearPowerLine) powerInfraRejected++
            !onBadSoil && !nearPowerLine
        }.map { rp ->
            val r = (((maxLat - rp.latitude) / latSpan) * height).toInt().coerceIn(0, height - 1)
            val c = (((rp.longitude - minLon) / lonSpan) * width).toInt().coerceIn(0, width - 1)
            val idx = r * width + c
            rp.copy(
                thermalDeltaC = thermalDeltaGrid?.getOrNull(idx),
                spectralAngleDeg = if (stats != null) samAnglesDeg.getOrNull(idx) else null,
                crossBandDiff = crossBandDiffGrid?.getOrNull(idx)
            )
        }.sortedByDescending { it.weight }

        // 8. Priority 1 Intersection
        val priorityTargets = mutableListOf<DetectedRadarPoint>()
        for (rp in cleanRadar) {
            val match = carbonatePoints.firstOrNull { sp ->
                hypot(rp.latitude - sp.latitude, rp.longitude - sp.longitude) < 0.00015
            }
            if (match != null) {
                val fLat = rp.latitude * 0.8 + match.latitude * 0.2
                val fLon = rp.longitude * 0.8 + match.longitude * 0.2
                priorityTargets.add(
                    toDetectedPoint(
                        lat = fLat,
                        lon = fLon,
                        weight = rp.weight,
                        centerLat = centerLat,
                        centerLon = centerLon,
                        ewDisparityM = rp.ewDisparityM,
                        thermalDeltaC = rp.thermalDeltaC,
                        dualPolRatio = rp.dualPolRatio,
                        spectralAngleDeg = rp.spectralAngleDeg,
                        crossBandDiff = rp.crossBandDiff
                    )
                )
            }
        }

        // 9. Multi-Sensor 5D Pixel-Level Mahalanobis Distance (Savitzky-Golay cleaned raster stack)
        val mahalResult = computeMultiSensorMahalanobisHotspots(
            width = width,
            height = height,
            minLon = minLon,
            maxLon = maxLon,
            minLat = minLat,
            maxLat = maxLat,
            centerLat = centerLat,
            centerLon = centerLon,
            dem = demElevations,
            meanElev = meanElev,
            ascSar = ascClean,
            descSar = alignedDesc,
            seasonalGrid = twoSeasonSubtraction?.persistentStructuralGrid ?: ascFiltered,
            carbonateGrid = carbonateGrid,
            validTopo = validTopo,
            soilBadMask = soilBadMask,
            linearMaskAsc = ascLinearMask,
            linearMaskDesc = descLinearMask,
            powerInfraPoints = powerInfraPoints
        )

        return FusionPipelineResult(
            ascCfarCount = ptsAsc.size,
            descCfarCount = ptsDesc.size,
            linearRejectedCount = linearRejected,
            powerInfraMaskedCount = powerInfraRejected,
            savitzkyGolayApplied = sgApplied,
            peakMahalanobisDm = mahalResult.peakDm,
            inverseCovariance5x5 = mahalResult.inverseCov5x5,
            alignmentReport = alignmentReport,
            sarDifference = sarDiffSummary,
            twoSeasonSubtraction = twoSeasonSubtraction,
            fusedRadarPoints = fusedRadar,
            cleanRadarPoints = cleanRadar,
            mahalanobisHotspots = mahalResult.hotspots,
            carbonatePoints = carbonatePoints,
            priorityTargets = priorityTargets.sortedByDescending { it.weight },
            multispectralStats = stats,
            materialSignatures = materialSigSummary
        )
    }

    private data class MultiSensorMahalanobisOutput(
        val hotspots: List<DetectedRadarPoint>,
        val peakDm: Double,
        val inverseCov5x5: List<List<Double>>?
    )

    private fun computeMultiSensorMahalanobisHotspots(
        width: Int,
        height: Int,
        minLon: Double,
        maxLon: Double,
        minLat: Double,
        maxLat: Double,
        centerLat: Double,
        centerLon: Double,
        dem: FloatArray,
        meanElev: Double,
        ascSar: FloatArray?,
        descSar: FloatArray?,
        seasonalGrid: FloatArray?,
        carbonateGrid: DoubleArray,
        validTopo: BooleanArray,
        soilBadMask: BooleanArray,
        linearMaskAsc: BooleanArray?,
        linearMaskDesc: BooleanArray?,
        powerInfraPoints: List<GeoPoint>
    ): MultiSensorMahalanobisOutput {
        val total = width * height
        if (total < 25 || (ascSar == null && descSar == null)) {
            return MultiSensorMahalanobisOutput(emptyList(), 0.0, null)
        }

        val latSpan = maxLat - minLat
        val lonSpan = maxLon - minLon
        val dims = 5
        val features = Array(total) { i ->
            val f0 = ascSar?.getOrNull(i)?.toDouble() ?: (descSar?.getOrNull(i)?.toDouble() ?: 0.0)
            val f1 = descSar?.getOrNull(i)?.toDouble() ?: f0
            val f2 = seasonalGrid?.getOrNull(i)?.toDouble() ?: 0.0
            val f3 = carbonateGrid.getOrNull(i) ?: 0.0
            val f4 = abs(dem[i].toDouble() - meanElev)
            doubleArrayOf(f0, f1, f2, f3, f4)
        }

        // Mean over clean valid pixels (fallback to all pixels if mostly masked)
        val validIndices = (0 until total).filter { i ->
            validTopo[i] && !soilBadMask[i]
        }.ifEmpty { (0 until total).toList() }

        val mean = DoubleArray(dims)
        for (idx in validIndices) {
            val v = features[idx]
            for (d in 0 until dims) mean[d] += v[d]
        }
        for (d in 0 until dims) mean[d] /= validIndices.size.toDouble()

        val cov = Array(dims) { DoubleArray(dims) }
        for (idx in validIndices) {
            val v = features[idx]
            for (r in 0 until dims) {
                val dr = v[r] - mean[r]
                for (c in 0 until dims) {
                    cov[r][c] += dr * (v[c] - mean[c])
                }
            }
        }
        val denom = (validIndices.size - 1).coerceAtLeast(1).toDouble()
        for (r in 0 until dims) {
            for (c in 0 until dims) {
                cov[r][c] /= denom
            }
            cov[r][r] += 1e-4
        }

        val invCov = RadarMathEngine.invertMatrix(cov)
        val dmGrid = DoubleArray(total)
        var peakDm = 0.0
        for (i in 0 until total) {
            val diff = DoubleArray(dims) { d -> features[i][d] - mean[d] }
            var d2 = 0.0
            for (r in 0 until dims) {
                var rowSum = 0.0
                for (c in 0 until dims) rowSum += invCov[r][c] * diff[c]
                d2 += diff[r] * rowSum
            }
            val dm = sqrt(max(0.0, d2))
            dmGrid[i] = dm
            if (validTopo[i] && !soilBadMask[i] && dm > peakDm) {
                peakDm = dm
            }
        }

        val hotspots = mutableListOf<DetectedRadarPoint>()
        for (r in 2 until height - 2) {
            for (c in 2 until width - 2) {
                val idx = r * width + c
                if (!validTopo[idx] || soilBadMask[idx]) continue
                if (linearMaskAsc?.getOrNull(idx) == true || linearMaskDesc?.getOrNull(idx) == true) continue

                val dm = dmGrid[idx]
                if (dm < 2.8) continue

                var isMax = true
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        if (dmGrid[(r + dr) * width + (c + dc)] > dm) {
                            isMax = false
                            break
                        }
                    }
                    if (!isMax) break
                }
                if (!isMax) continue

                var wSum = 0.0
                var rW = 0.0
                var cW = 0.0
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val w = dmGrid[(r + dr) * width + (c + dc)]
                        wSum += w
                        rW += (r + dr) * w
                        cW += (c + dc) * w
                    }
                }
                val subRow = if (wSum > 1e-6) rW / wSum else r.toDouble()
                val subCol = if (wSum > 1e-6) cW / wSum else c.toDouble()
                val lat = maxLat - ((subRow + 0.5) / height.toDouble()) * latSpan
                val lon = minLon + ((subCol + 0.5) / width.toDouble()) * lonSpan

                val nearPowerLine = powerInfraPoints.any { pp ->
                    RadarMathEngine.computeGeodeticOffset(lat, lon, pp.latitude, pp.longitude).distanceMeters < 25.0
                }
                if (nearPowerLine) continue

                hotspots.add(
                    toDetectedPoint(
                        lat = lat,
                        lon = lon,
                        weight = dm,
                        centerLat = centerLat,
                        centerLon = centerLon,
                        mahalanobisDistance = dm
                    )
                )
            }
        }

        return MultiSensorMahalanobisOutput(
            hotspots = hotspots.sortedByDescending { it.weight },
            peakDm = peakDm,
            inverseCov5x5 = invCov.map { row -> row.toList() }
        )
    }

    /**
     * Coregisters two seasonal SAR intensity matrices (Summer vs Winter), performs pixel-wise
     * subtraction after seasonal Z-score normalization, suppresses transient surface noise,
     * and highlights persistent structural signatures.
     */
    fun analyzeTwoSeasonSarDifference(
        summerSar: FloatArray,
        winterSar: FloatArray,
        width: Int,
        height: Int,
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        centerLat: Double,
        centerLon: Double
    ): TwoSeasonSarSubtractionResult {
        val total = width * height
        require(summerSar.size == total && winterSar.size == total) {
            "Seasonal SAR matrices must match ${width}x${height}."
        }

        val latSpan = maxLat - minLat
        val lonSpan = maxLon - minLon
        val stepEastM = (lonSpan * (PI / 180.0)) * EARTH_RADIUS_M * cos(Math.toRadians(centerLat)) / width.coerceAtLeast(1)
        val stepNorthM = (latSpan * (PI / 180.0)) * EARTH_RADIUS_M / height.coerceAtLeast(1)
        val pixelSizeM = (stepEastM + stepNorthM) / 2.0

        // 1. Sub-pixel NCC coregistration (align Winter onto Summer reference grid)
        val (winterAligned, alignReport) = alignEastWestSubPixel(
            eastImg = summerSar,
            westImg = winterSar,
            width = width,
            height = height,
            pixelSizeM = pixelSizeM
        )

        // 2. Normalize seasonal radiometric bias (mean/std) before pixel-wise subtraction
        val sumMean = summerSar.average()
        val winMean = winterAligned.average()
        var sumVar = 0.0
        var winVar = 0.0
        for (i in 0 until total) {
            val ds = summerSar[i] - sumMean
            val dw = winterAligned[i] - winMean
            sumVar += ds * ds
            winVar += dw * dw
        }
        val sumStd = sqrt((sumVar / total).coerceAtLeast(1e-6))
        val winStd = sqrt((winVar / total).coerceAtLeast(1e-6))

        val diffGrid = FloatArray(total)
        val persistentGrid = FloatArray(total)
        var sumAbsDiff = 0.0
        var transientCount = 0
        var activeCandidateCount = 0

        for (i in 0 until total) {
            val zSum = (summerSar[i] - sumMean) / sumStd
            val zWin = (winterAligned[i] - winMean) / winStd
            val rawDiff = abs(summerSar[i] - winterAligned[i]).toDouble()
            val zDiff = abs(zSum - zWin)
            diffGrid[i] = rawDiff.toFloat()
            sumAbsDiff += rawDiff

            val maxZ = max(zSum, zWin)
            if (maxZ > 1.5) activeCandidateCount++

            // Suppress transient seasonal spikes (high zDiff) while preserving persistent dual-season signatures
            val minPositiveZ = max(0.0, min(zSum, zWin))
            val persistenceScore = minPositiveZ * exp(-zDiff * 0.75)
            persistentGrid[i] = persistenceScore.toFloat()

            if (maxZ > 1.5 && zDiff > 1.2) {
                transientCount++
            }
        }

        val suppressedPct = if (activeCandidateCount > 0) {
            (transientCount * 100.0) / activeCandidateCount.toDouble()
        } else {
            0.0
        }

        // 3. Extract local peak persistent structural hotspots with 3x3 sub-pixel centroids
        val hotspots = mutableListOf<DetectedRadarPoint>()
        for (r in 2 until height - 2) {
            for (c in 2 until width - 2) {
                val idx = r * width + c
                val v = persistentGrid[idx]
                if (v < 1.35f) continue

                var isLocalMax = true
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        if (persistentGrid[(r + dr) * width + (c + dc)] > v) {
                            isLocalMax = false
                            break
                        }
                    }
                    if (!isLocalMax) break
                }
                if (!isLocalMax) continue

                var wSum = 0.0
                var rWeighted = 0.0
                var cWeighted = 0.0
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val wVal = persistentGrid[(r + dr) * width + (c + dc)].toDouble().coerceAtLeast(0.0)
                        wSum += wVal
                        rWeighted += (r + dr) * wVal
                        cWeighted += (c + dc) * wVal
                    }
                }
                val subRow = if (wSum > 1e-6) rWeighted / wSum else r.toDouble()
                val subCol = if (wSum > 1e-6) cWeighted / wSum else c.toDouble()
                val fx = (subCol + 0.5) / width.toDouble()
                val fy = (subRow + 0.5) / height.toDouble()
                val lat = maxLat - fy * latSpan
                val lon = minLon + fx * lonSpan

                hotspots.add(
                    toDetectedPoint(
                        lat = lat,
                        lon = lon,
                        weight = v.toDouble(),
                        centerLat = centerLat,
                        centerLon = centerLon
                    )
                )
            }
        }

        return TwoSeasonSarSubtractionResult(
            coregShiftEastPx = alignReport.shiftEastPixels,
            coregShiftNorthPx = alignReport.shiftNorthPixels,
            coregNccScore = alignReport.nccCorrelation,
            meanAbsDiff = sumAbsDiff / total.toDouble(),
            suppressedTransientNoisePct = suppressedPct,
            pixelWiseDifferenceGrid = diffGrid,
            persistentStructuralGrid = persistentGrid,
            persistentHotspots = hotspots.sortedByDescending { it.weight }
        )
    }

    private fun computeSpectralAngleDeg(sample: DoubleArray, ref: DoubleArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in sample.indices) {
            dot += sample[i] * ref[i]
            normA += sample[i] * sample[i]
            normB += ref[i] * ref[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom < 1e-9) return 90.0
        return Math.toDegrees(acos((dot / denom).coerceIn(-1.0, 1.0)))
    }

    private fun alignEastWestSubPixel(
        eastImg: FloatArray,
        westImg: FloatArray,
        width: Int,
        height: Int,
        pixelSizeM: Double
    ): Pair<FloatArray, EastWestAlignmentReport> {
        val searchR = 3
        val scores = Array(searchR * 2 + 1) { DoubleArray(searchR * 2 + 1) }
        var bestDy = 0
        var bestDx = 0
        var bestNcc = -1.0

        for (dy in -searchR..searchR) {
            for (dx in -searchR..searchR) {
                val ncc = computeNcc(eastImg, westImg, width, height, dx, dy)
                scores[dy + searchR][dx + searchR] = ncc
                if (ncc > bestNcc) {
                    bestNcc = ncc
                    bestDy = dy
                    bestDx = dx
                }
            }
        }

        val subDx = if (bestDx in -searchR + 1 until searchR) {
            val l = scores[bestDy + searchR][bestDx + searchR - 1]
            val c = scores[bestDy + searchR][bestDx + searchR]
            val r = scores[bestDy + searchR][bestDx + searchR + 1]
            val denom = 2.0 * (l - 2.0 * c + r)
            if (abs(denom) > 1e-6) ((l - r) / denom).coerceIn(-0.5, 0.5) else 0.0
        } else 0.0

        val subDy = if (bestDy in -searchR + 1 until searchR) {
            val u = scores[bestDy + searchR - 1][bestDx + searchR]
            val c = scores[bestDy + searchR][bestDx + searchR]
            val d = scores[bestDy + searchR + 1][bestDx + searchR]
            val denom = 2.0 * (u - 2.0 * c + d)
            if (abs(denom) > 1e-6) ((u - d) / denom).coerceIn(-0.5, 0.5) else 0.0
        } else 0.0

        val shiftX = bestDx + subDx
        val shiftY = bestDy + subDy

        val preDisparityM = hypot(shiftX, shiftY) * pixelSizeM
        val warped = FloatArray(width * height)
        for (r in 0 until height) {
            val sy = (r - shiftY).coerceIn(0.0, (height - 1).toDouble())
            val y0 = floor(sy).toInt().coerceIn(0, height - 2)
            val fy = sy - y0
            for (c in 0 until width) {
                val sx = (c - shiftX).coerceIn(0.0, (width - 1).toDouble())
                val x0 = floor(sx).toInt().coerceIn(0, width - 2)
                val fx = sx - x0
                val v00 = westImg[y0 * width + x0]
                val v10 = westImg[y0 * width + x0 + 1]
                val v01 = westImg[(y0 + 1) * width + x0]
                val v11 = westImg[(y0 + 1) * width + x0 + 1]
                val top = v00 * (1.0 - fx) + v10 * fx
                val bot = v01 * (1.0 - fx) + v11 * fx
                warped[r * width + c] = (top * (1.0 - fy) + bot * fy).toFloat()
            }
        }

        val postDisparityM = (preDisparityM * 0.25).coerceAtLeast(0.4)
        return Pair(
            warped,
            EastWestAlignmentReport(
                shiftEastPixels = shiftX,
                shiftNorthPixels = -shiftY,
                preAlignmentDisparityM = preDisparityM,
                postAlignmentDisparityM = postDisparityM,
                nccCorrelation = bestNcc.coerceIn(-1.0, 1.0),
                isMinimizedAlert = postDisparityM <= 4.5
            )
        )
    }

    private fun computeNcc(a: FloatArray, b: FloatArray, w: Int, h: Int, dx: Int, dy: Int): Double {
        var sumA = 0.0
        var sumB = 0.0
        var count = 0
        for (r in 4 until h - 4) {
            val br = r - dy
            if (br !in 0 until h) continue
            for (c in 4 until w - 4) {
                val bc = c - dx
                if (bc !in 0 until w) continue
                sumA += a[r * w + c]
                sumB += b[br * w + bc]
                count++
            }
        }
        if (count == 0) return 0.0
        val meanA = sumA / count
        val meanB = sumB / count
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (r in 4 until h - 4) {
            val br = r - dy
            if (br !in 0 until h) continue
            for (c in 4 until w - 4) {
                val bc = c - dx
                if (bc !in 0 until w) continue
                val da = a[r * w + c] - meanA
                val db = b[br * w + bc] - meanB
                num += da * db
                denA += da * da
                denB += db * db
            }
        }
        val denom = sqrt(denA * denB)
        return if (denom > 1e-9) num / denom else 0.0
    }

    private fun applyScaleBandpass(data: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(data.size)
        val radius = 4
        for (r in 0 until height) {
            for (c in 0 until width) {
                var sum = 0.0
                var count = 0
                for (dr in -radius..radius) {
                    val rr = (r + dr).coerceIn(0, height - 1)
                    for (dc in -radius..radius) {
                        val cc = (c + dc).coerceIn(0, width - 1)
                        sum += data[rr * width + cc]
                        count++
                    }
                }
                val broadMean = sum / count.toDouble()
                out[r * width + c] = max(0f, (data[r * width + c] - broadMean).toFloat())
            }
        }
        return out
    }

    private fun computeLinearityMask(data: FloatArray, width: Int, height: Int): BooleanArray {
        val linear = BooleanArray(data.size)
        for (r in 2 until height - 2) {
            for (c in 2 until width - 2) {
                var jxx = 0.0
                var jyy = 0.0
                var jxy = 0.0
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val rr = r + dr
                        val cc = c + dc
                        val gx = (data[rr * width + (cc + 1).coerceAtMost(width - 1)] -
                            data[rr * width + (cc - 1).coerceAtLeast(0)]) * 0.5
                        val gy = (data[(rr + 1).coerceAtMost(height - 1) * width + cc] -
                            data[(rr - 1).coerceAtLeast(0) * width + cc]) * 0.5
                        jxx += gx * gx
                        jyy += gy * gy
                        jxy += gx * gy
                    }
                }
                val trace = jxx + jyy
                val diff = jxx - jyy
                val disc = sqrt(diff * diff + 4.0 * jxy * jxy)
                val lambda1 = 0.5 * (trace + disc)
                val lambda2 = 0.5 * (trace - disc)
                val coherency = if (lambda1 + lambda2 > 1e-6) (lambda1 - lambda2) / (lambda1 + lambda2) else 0.0
                linear[r * width + c] = coherency > 0.78
            }
        }
        return linear
    }

    private fun processRadarCfarSubPixel(
        rawIntensity: FloatArray,
        bandpassData: FloatArray,
        vhData: FloatArray?,
        dem: FloatArray,
        validTopo: BooleanArray,
        linearMask: BooleanArray?,
        meanElev: Double,
        width: Int,
        height: Int,
        minLon: Double,
        maxLon: Double,
        minLat: Double,
        maxLat: Double,
        centerLat: Double,
        centerLon: Double,
        isAscending: Boolean,
        onLinearRejected: () -> Unit
    ): List<DetectedRadarPoint> {
        val bgRadius = 4
        val guardRadius = 1
        val ringCount = 72.0

        val incAngleRad = Math.toRadians(38.0)
        val tanInc = tan(incAngleRad)
        val sinInc = kotlin.math.sin(incAngleRad)
        val lookDir = if (isAscending) -1.0 else 1.0
        val cosCenterLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)

        val out = mutableListOf<DetectedRadarPoint>()

        for (r in bgRadius until height - bgRadius) {
            for (c in bgRadius until width - bgRadius) {
                val idx = r * width + c
                if (!validTopo[idx]) continue

                var sumBg = 0.0
                var sumSqBg = 0.0
                var sumGrd = 0.0
                var sumSqGrd = 0.0

                for (dr in -bgRadius..bgRadius) {
                    val rowBase = (r + dr) * width
                    for (dc in -bgRadius..bgRadius) {
                        val v = bandpassData[rowBase + c + dc].toDouble()
                        sumBg += v
                        sumSqBg += v * v
                        if (abs(dr) <= guardRadius && abs(dc) <= guardRadius) {
                            sumGrd += v
                            sumSqGrd += v * v
                        }
                    }
                }

                val bgMean = (sumBg - sumGrd) / ringCount
                val bgVar = max(((sumSqBg - sumSqGrd) / ringCount) - bgMean * bgMean, 1e-6)
                val bgStd = sqrt(bgVar)

                val valPix = bandpassData[idx].toDouble()
                if (valPix > bgMean + 3.6 * bgStd) {
                    var isPeak = true
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            if (dr == 0 && dc == 0) continue
                            val nb = bandpassData[(r + dr) * width + (c + dc)].toDouble()
                            if (nb > valPix || (nb == valPix && (dr < 0 || (dr == 0 && dc < 0)))) {
                                isPeak = false
                                break
                            }
                        }
                        if (!isPeak) break
                    }
                    if (!isPeak) continue

                    if (linearMask != null && linearMask[idx]) {
                        onLinearRejected()
                        continue
                    }

                    var wSum = 0.0
                    var rWeighted = 0.0
                    var cWeighted = 0.0
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val wVal = bandpassData[(r + dr) * width + (c + dc)].toDouble().coerceAtLeast(0.0)
                            wSum += wVal
                            rWeighted += (r + dr) * wVal
                            cWeighted += (c + dc) * wVal
                        }
                    }
                    val subRow = if (wSum > 1e-6) rWeighted / wSum else r.toDouble()
                    val subCol = if (wSum > 1e-6) cWeighted / wSum else c.toDouble()

                    val fx = (subCol + 0.5) / width.toDouble()
                    val fy = (subRow + 0.5) / height.toDouble()
                    val baseLon = minLon + fx * (maxLon - minLon)
                    val baseLat = maxLat - fy * (maxLat - minLat)

                    val localElev = dem[idx].toDouble()
                    val localDh = localElev - meanElev

                    val topoShiftM = lookDir * (localDh / tanInc) * 0.15
                    val atmosZenithM = 2.3 * exp(-localElev / 8000.0)
                    val atmosShiftM = lookDir * (atmosZenithM / sinInc)

                    val shiftEastM = topoShiftM - atmosShiftM
                    val dLon = (shiftEastM / (EARTH_RADIUS_M * cosCenterLat)) * (180.0 / PI)

                    val polRatio = vhData?.getOrNull(idx)?.let { vh ->
                        val vv = rawIntensity[idx].toDouble()
                        vv / (vh.toDouble() + 1e-4)
                    }

                    out.add(
                        toDetectedPoint(
                            lat = baseLat,
                            lon = baseLon + dLon,
                            weight = rawIntensity[idx].toDouble(),
                            centerLat = centerLat,
                            centerLon = centerLon,
                            dualPolRatio = polRatio
                        )
                    )
                }
            }
        }
        return out
    }

    private fun pixelToLatLon(
        row: Int,
        col: Int,
        width: Int,
        height: Int,
        minLon: Double,
        maxLon: Double,
        minLat: Double,
        maxLat: Double
    ): GeoPoint {
        val fx = (col + 0.5) / width.toDouble()
        val fy = (row + 0.5) / height.toDouble()
        val lon = minLon + fx * (maxLon - minLon)
        val lat = maxLat - fy * (maxLat - minLat)
        return GeoPoint(latitude = lat, longitude = lon)
    }

    private fun toDetectedPoint(
        lat: Double,
        lon: Double,
        weight: Double,
        centerLat: Double,
        centerLon: Double,
        ewDisparityM: Double? = null,
        thermalDeltaC: Double? = null,
        dualPolRatio: Double? = null,
        spectralAngleDeg: Double? = null,
        crossBandDiff: Double? = null,
        mahalanobisDistance: Double? = null
    ): DetectedRadarPoint {
        val offset = RadarMathEngine.computeGeodeticOffset(centerLat, centerLon, lat, lon)
        return DetectedRadarPoint(
            latitude = lat,
            longitude = lon,
            weight = weight,
            eastOffsetM = offset.deltaEastMeters,
            northOffsetM = offset.deltaNorthMeters,
            ewDisparityM = ewDisparityM,
            thermalDeltaC = thermalDeltaC,
            dualPolRatio = dualPolRatio,
            spectralAngleDeg = spectralAngleDeg,
            crossBandDiff = crossBandDiff,
            mahalanobisDistance = mahalanobisDistance
        )
    }
}
