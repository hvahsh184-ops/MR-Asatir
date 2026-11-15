package com.example.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class UtmCoordinate(
    val zone: Int,
    val hemisphere: Char,
    val easting: Double,
    val northing: Double
)

data class QuaternionData(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double
)

data class AtmosphericCorrection(
    val pressureHpa: Double,
    val temperatureC: Double,
    val relativeHumidityPct: Double,
    val soilMoistureM3: Double,
    val waterVaporPressureHpa: Double,
    val refractivityN: Double,
    val zenithHydrostaticDelayM: Double,
    val zenithWetDelayM: Double,
    val slantRangeDelayM: Double
)

data class GridCellAnalysis(
    val row: Int,
    val col: Int,
    val latitude: Double,
    val longitude: Double,
    val eastOffsetM: Double,
    val northOffsetM: Double,
    val rawElevationM: Double,
    val detrendedResidualM: Double,
    val slopeDeg: Double,
    val laplacianCurvature: Double,
    val tpiM: Double,
    val mahalanobisDistance: Double
)

data class GeodeticOffsetResult(
    val distanceMeters: Double,
    val forwardAzimuthDeg: Double,
    val backAzimuthDeg: Double,
    val deltaEastMeters: Double,
    val deltaNorthMeters: Double,
    val deltaElevationMeters: Double?,
    val fromUtm: UtmCoordinate,
    val toUtm: UtmCoordinate
)

data class MatrixAnalysisSummary(
    val gridSize: Int,
    val planeCoefficients: List<Double>,
    val covarianceMatrix4x4: List<List<Double>>,
    val inverseCovarianceMatrix4x4: List<List<Double>>,
    val meanElevationM: Double,
    val peakAnomalyCell: GridCellAnalysis,
    val cells: List<GridCellAnalysis>
)

object RadarMathEngine {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val WGS84_A = 6_378_137.0
    private const val WGS84_F = 1.0 / 298.257223563
    private const val WGS84_E2 = WGS84_F * (2.0 - WGS84_F)

    fun generateSpatialGrid(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        gridSize: Int = 7
    ): List<GeoPoint> {
        val clampedRadius = radiusMeters.coerceIn(30.0, 25_000.0)
        val half = gridSize / 2
        val stepM = clampedRadius / half.toDouble()
        val points = ArrayList<GeoPoint>(gridSize * gridSize)
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.05)

        for (r in 0 until gridSize) {
            val northM = (half - r) * stepM
            for (c in 0 until gridSize) {
                val eastM = (c - half) * stepM
                val dLat = (northM / EARTH_RADIUS_M) * (180.0 / PI)
                val dLon = (eastM / (EARTH_RADIUS_M * cosLat)) * (180.0 / PI)
                points.add(
                    GeoPoint(
                        latitude = (centerLat + dLat).coerceIn(-89.9, 89.9),
                        longitude = normalizeLongitude(centerLon + dLon)
                    )
                )
            }
        }
        return points
    }

    fun computeAtmosphericCorrection(
        pressureHpa: Double,
        temperatureC: Double,
        relativeHumidityPct: Double,
        soilMoistureM3: Double,
        centerLat: Double,
        meanElevationM: Double,
        incidenceAngleDeg: Double = 38.0
    ): AtmosphericCorrection {
        val tempK = (temperatureC + 273.15).coerceAtLeast(180.0)
        val satVaporHpa = 6.112 * exp((17.67 * temperatureC) / (temperatureC + 243.5))
        val eHpa = (relativeHumidityPct.coerceIn(0.0, 100.0) / 100.0) * satVaporHpa

        // Smith-Weintraub radio refractivity N (ppm)
        val refractivityN = (77.6 * (pressureHpa / tempK)) + (3.73e5 * (eHpa / (tempK * tempK)))

        // Saastamoinen zenith delays
        val latRad = Math.toRadians(centerLat)
        val hKm = meanElevationM / 1000.0
        val denom = 1.0 - 0.00266 * cos(2.0 * latRad) - 0.00028 * hKm
        val zhd = (0.0022768 * pressureHpa) / denom.coerceAtLeast(0.8)
        val zwd = 0.002277 * ((1255.0 / tempK) + 0.05) * eHpa

        val cosInc = cos(Math.toRadians(incidenceAngleDeg)).coerceAtLeast(0.25)
        val slantDelay = (zhd + zwd) / cosInc

        return AtmosphericCorrection(
            pressureHpa = pressureHpa,
            temperatureC = temperatureC,
            relativeHumidityPct = relativeHumidityPct,
            soilMoistureM3 = soilMoistureM3,
            waterVaporPressureHpa = eHpa,
            refractivityN = refractivityN,
            zenithHydrostaticDelayM = zhd,
            zenithWetDelayM = zwd,
            slantRangeDelayM = slantDelay
        )
    }

    fun analyzeSurfaceMatrix(
        gridPoints: List<GeoPoint>,
        elevationsM: List<Double>,
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        gridSize: Int = 7
    ): MatrixAnalysisSummary {
        require(gridPoints.size == gridSize * gridSize && elevationsM.size == gridPoints.size) {
            "Grid points and elevations must match ${gridSize}x${gridSize}."
        }

        val half = gridSize / 2
        val stepM = radiusMeters.coerceIn(30.0, 25_000.0) / half.toDouble()
        val n = gridPoints.size

        val eastM = DoubleArray(n)
        val northM = DoubleArray(n)
        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                val idx = r * gridSize + c
                eastM[idx] = (c - half) * stepM
                northM[idx] = (half - r) * stepM
            }
        }

        // 1. Fit planar surface z = a*x + b*y + c via normal equations (A^T A)^-1 A^T z
        val ata = Array(3) { DoubleArray(3) }
        val atz = DoubleArray(3)
        for (i in 0 until n) {
            val x = eastM[i]
            val y = northM[i]
            val z = elevationsM[i]
            val rowVec = doubleArrayOf(x, y, 1.0)
            for (r in 0 until 3) {
                atz[r] += rowVec[r] * z
                for (c in 0 until 3) {
                    ata[r][c] += rowVec[r] * rowVec[c]
                }
            }
        }
        for (d in 0 until 3) ata[d][d] += 1e-6
        val invAta = invertMatrix(ata)
        val planeCoeffs = DoubleArray(3)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                planeCoeffs[r] += invAta[r][c] * atz[c]
            }
        }

        val residuals = DoubleArray(n) { i ->
            val fitted = planeCoeffs[0] * eastM[i] + planeCoeffs[1] * northM[i] + planeCoeffs[2]
            elevationsM[i] - fitted
        }

        // 2. Modified Surface Topology: Slope, Laplacian Curvature, and Topographic Position Index (TPI)
        val slopesDeg = DoubleArray(n)
        val curvatures = DoubleArray(n)
        val tpis = DoubleArray(n)

        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                val idx = r * gridSize + c
                val left = elevationsM[r * gridSize + (c - 1).coerceAtLeast(0)]
                val right = elevationsM[r * gridSize + (c + 1).coerceAtMost(gridSize - 1)]
                val up = elevationsM[(r - 1).coerceAtLeast(0) * gridSize + c]
                val down = elevationsM[(r + 1).coerceAtMost(gridSize - 1) * gridSize + c]

                val dxSpan = if (c == 0 || c == gridSize - 1) stepM else 2.0 * stepM
                val dySpan = if (r == 0 || r == gridSize - 1) stepM else 2.0 * stepM
                val dzDx = (right - left) / dxSpan
                val dzDy = (up - down) / dySpan
                slopesDeg[idx] = Math.toDegrees(atan2(sqrt(dzDx * dzDx + dzDy * dzDy), 1.0))

                val d2x = (right - 2.0 * elevationsM[idx] + left) / (stepM * stepM)
                val d2y = (up - 2.0 * elevationsM[idx] + down) / (stepM * stepM)
                curvatures[idx] = (d2x + d2y) * 1000.0

                var neighborSum = 0.0
                var neighborCount = 0
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = r + dr
                        val nc = c + dc
                        if (nr in 0 until gridSize && nc in 0 until gridSize) {
                            neighborSum += elevationsM[nr * gridSize + nc]
                            neighborCount++
                        }
                    }
                }
                tpis[idx] = elevationsM[idx] - (neighborSum / neighborCount.coerceAtLeast(1))
            }
        }

        // 3. 4D Feature Vectors & 4x4 Covariance Matrix Inversion for Mahalanobis Distance
        val features = Array(n) { i ->
            doubleArrayOf(residuals[i], slopesDeg[i], curvatures[i], tpis[i])
        }
        val means = DoubleArray(4)
        for (i in 0 until n) {
            for (k in 0 until 4) means[k] += features[i][k]
        }
        for (k in 0 until 4) means[k] /= n.toDouble()

        val cov = Array(4) { DoubleArray(4) }
        for (i in 0 until n) {
            for (r in 0 until 4) {
                val dr = features[i][r] - means[r]
                for (c in 0 until 4) {
                    val dc = features[i][c] - means[c]
                    cov[r][c] += dr * dc
                }
            }
        }
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                cov[r][c] /= (n - 1).coerceAtLeast(1).toDouble()
            }
            // Tikhonov regularization for numerical stability on flat terrain
            cov[r][r] += 1e-4
        }

        val invCov = invertMatrix(cov)

        val cells = ArrayList<GridCellAnalysis>(n)
        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                val i = r * gridSize + c
                val diff = DoubleArray(4) { k -> features[i][k] - means[k] }
                var d2 = 0.0
                for (row in 0 until 4) {
                    var rowAcc = 0.0
                    for (col in 0 until 4) {
                        rowAcc += invCov[row][col] * diff[col]
                    }
                    d2 += diff[row] * rowAcc
                }
                val mahalanobis = sqrt(d2.coerceAtLeast(0.0))
                cells.add(
                    GridCellAnalysis(
                        row = r,
                        col = c,
                        latitude = gridPoints[i].latitude,
                        longitude = gridPoints[i].longitude,
                        eastOffsetM = eastM[i],
                        northOffsetM = northM[i],
                        rawElevationM = elevationsM[i],
                        detrendedResidualM = residuals[i],
                        slopeDeg = slopesDeg[i],
                        laplacianCurvature = curvatures[i],
                        tpiM = tpis[i],
                        mahalanobisDistance = mahalanobis
                    )
                )
            }
        }

        val peak = cells.maxByOrNull { it.mahalanobisDistance } ?: cells.first()

        return MatrixAnalysisSummary(
            gridSize = gridSize,
            planeCoefficients = planeCoeffs.toList(),
            covarianceMatrix4x4 = cov.map { it.toList() },
            inverseCovarianceMatrix4x4 = invCov.map { it.toList() },
            meanElevationM = elevationsM.average(),
            peakAnomalyCell = peak,
            cells = cells
        )
    }

    fun invertMatrix(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val size = matrix.size
        val augmented = Array(size) { r ->
            DoubleArray(size * 2) { c ->
                if (c < size) matrix[r][c] else if (c - size == r) 1.0 else 0.0
            }
        }

        for (i in 0 until size) {
            var pivotRow = i
            var maxVal = abs(augmented[i][i])
            for (r in i + 1 until size) {
                val candidate = abs(augmented[r][i])
                if (candidate > maxVal) {
                    maxVal = candidate
                    pivotRow = r
                }
            }
            if (pivotRow != i) {
                val temp = augmented[i]
                augmented[i] = augmented[pivotRow]
                augmented[pivotRow] = temp
            }

            val pivot = augmented[i][i].let { if (abs(it) < 1e-9) 1e-9 else it }
            for (c in 0 until size * 2) {
                augmented[i][c] /= pivot
            }

            for (r in 0 until size) {
                if (r != i) {
                    val factor = augmented[r][i]
                    for (c in 0 until size * 2) {
                        augmented[r][c] -= factor * augmented[i][c]
                    }
                }
            }
        }

        return Array(size) { r ->
            DoubleArray(size) { c -> augmented[r][c + size] }
        }
    }

    fun rotationVectorToQuaternion(rv: FloatArray): QuaternionData {
        val x = rv.getOrElse(0) { 0f }.toDouble()
        val y = rv.getOrElse(1) { 0f }.toDouble()
        val z = rv.getOrElse(2) { 0f }.toDouble()
        val w = if (rv.size >= 4) {
            rv[3].toDouble()
        } else {
            val sq = 1.0 - (x * x + y * y + z * z)
            if (sq > 0.0) sqrt(sq) else 0.0
        }
        val norm = sqrt(w * w + x * x + y * y + z * z).coerceAtLeast(1e-9)
        val qw = w / norm
        val qx = x / norm
        val qy = y / norm
        val qz = z / norm

        // Roll, Pitch, Yaw from quaternion (ENU convention)
        val sinrCosp = 2.0 * (qw * qx + qy * qz)
        val cosrCosp = 1.0 - 2.0 * (qx * qx + qy * qy)
        val roll = Math.toDegrees(atan2(sinrCosp, cosrCosp))

        val sinp = (2.0 * (qw * qy - qz * qx)).coerceIn(-1.0, 1.0)
        val pitch = Math.toDegrees(kotlin.math.asin(sinp))

        val sinyCosp = 2.0 * (qw * qz + qx * qy)
        val cosyCosp = 1.0 - 2.0 * (qy * qy + qz * qz)
        // ENU Z-rotation is counter-clockwise; negate for standard clockwise compass azimuth (0°=N, 90°=E)
        val yaw = (-Math.toDegrees(atan2(sinyCosp, cosyCosp)) + 360.0) % 360.0

        return QuaternionData(
            w = qw,
            x = qx,
            y = qy,
            z = qz,
            yawDeg = yaw,
            pitchDeg = pitch,
            rollDeg = roll
        )
    }

    fun computeGeodeticOffset(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        fromElevationM: Double? = null,
        toElevationM: Double? = null
    ): GeodeticOffsetResult {
        val lat1 = Math.toRadians(fromLat)
        val lon1 = Math.toRadians(fromLon)
        val lat2 = Math.toRadians(toLat)
        val lon2 = Math.toRadians(toLon)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val a = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        val distanceM = EARTH_RADIUS_M * c

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val forwardAz = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        val backAz = (forwardAz + 180.0) % 360.0

        val meanLatRad = (lat1 + lat2) / 2.0
        val deltaNorth = dLat * EARTH_RADIUS_M
        val deltaEast = dLon * EARTH_RADIUS_M * cos(meanLatRad)
        val deltaElev = if (fromElevationM != null && toElevationM != null) {
            toElevationM - fromElevationM
        } else {
            null
        }

        return GeodeticOffsetResult(
            distanceMeters = distanceM,
            forwardAzimuthDeg = forwardAz,
            backAzimuthDeg = backAz,
            deltaEastMeters = deltaEast,
            deltaNorthMeters = deltaNorth,
            deltaElevationMeters = deltaElev,
            fromUtm = latLonToUtm(fromLat, fromLon),
            toUtm = latLonToUtm(toLat, toLon)
        )
    }

    fun offsetCoordinate(
        baseLat: Double,
        baseLon: Double,
        deltaEastM: Double,
        deltaNorthM: Double
    ): GeoPoint {
        val dLat = (deltaNorthM / EARTH_RADIUS_M) * (180.0 / PI)
        val cosLat = cos(Math.toRadians(baseLat)).coerceAtLeast(0.05)
        val dLon = (deltaEastM / (EARTH_RADIUS_M * cosLat)) * (180.0 / PI)
        return GeoPoint(
            latitude = (baseLat + dLat).coerceIn(-89.9, 89.9),
            longitude = normalizeLongitude(baseLon + dLon)
        )
    }

    fun latLonToUtm(latitude: Double, longitude: Double): UtmCoordinate {
        val latRad = Math.toRadians(latitude)
        val lonNorm = normalizeLongitude(longitude)
        val lonRad = Math.toRadians(lonNorm)

        val zone = (floor((lonNorm + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
        val lonOrigin = Math.toRadians(((zone - 1) * 6 - 180 + 3).toDouble())

        val ePrime2 = WGS84_E2 / (1.0 - WGS84_E2)
        val nVal = WGS84_A / sqrt(1.0 - WGS84_E2 * sin(latRad).pow(2.0))
        val tVal = tan(latRad).pow(2.0)
        val cVal = ePrime2 * cos(latRad).pow(2.0)
        val aVal = cos(latRad) * (lonRad - lonOrigin)

        val mVal = WGS84_A * (
            (1.0 - WGS84_E2 / 4.0 - 3.0 * WGS84_E2.pow(2.0) / 64.0 - 5.0 * WGS84_E2.pow(3.0) / 256.0) * latRad -
                (3.0 * WGS84_E2 / 8.0 + 3.0 * WGS84_E2.pow(2.0) / 32.0 + 45.0 * WGS84_E2.pow(3.0) / 1024.0) * sin(2.0 * latRad) +
                (15.0 * WGS84_E2.pow(2.0) / 256.0 + 45.0 * WGS84_E2.pow(3.0) / 1024.0) * sin(4.0 * latRad) -
                (35.0 * WGS84_E2.pow(3.0) / 3072.0) * sin(6.0 * latRad)
            )

        val k0 = 0.9996
        val easting = k0 * nVal * (
            aVal + (1.0 - tVal + cVal) * aVal.pow(3.0) / 6.0 +
                (5.0 - 18.0 * tVal + tVal * tVal + 72.0 * cVal - 58.0 * ePrime2) * aVal.pow(5.0) / 120.0
            ) + 500_000.0

        var northing = k0 * (
            mVal + nVal * tan(latRad) * (
                aVal * aVal / 2.0 +
                    (5.0 - tVal + 9.0 * cVal + 4.0 * cVal * cVal) * aVal.pow(4.0) / 24.0 +
                    (61.0 - 58.0 * tVal + tVal * tVal + 600.0 * cVal - 330.0 * ePrime2) * aVal.pow(6.0) / 720.0
                )
            )

        val hemi = if (latitude >= 0.0) 'N' else 'S'
        if (latitude < 0.0) northing += 10_000_000.0

        return UtmCoordinate(
            zone = zone,
            hemisphere = hemi,
            easting = (easting * 100.0).roundToInt() / 100.0,
            northing = (northing * 100.0).roundToInt() / 100.0
        )
    }

    private fun normalizeLongitude(lon: Double): Double {
        var res = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (res == -180.0 && lon > 0) res = 180.0
        return res
    }
}
