package com.example.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import com.example.data.remote.EarthSearchStacApi
import com.example.data.remote.OpenMeteoApi
import com.example.data.remote.OpenMeteoElevationResponse
import com.example.data.remote.OverpassApi
import com.example.data.remote.PlanetaryComputerApi
import com.example.data.remote.StacEqFilter
import com.example.data.remote.StacFeature
import com.example.data.remote.StacFeatureCollection
import com.example.data.remote.StacInFilter
import com.example.data.remote.StacLtFilter
import com.example.data.remote.StacQuery
import com.example.data.remote.StacSearchRequest
import com.example.domain.AtmosphericCorrection
import com.example.domain.FusionPipelineResult
import com.example.domain.GeoPoint
import com.example.domain.MatrixAnalysisSummary
import com.example.domain.RadarMathEngine
import com.example.domain.SarOpticalFusionEngine
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

data class SatellitePassItem(
    val id: String,
    val collection: String,
    val bandLabel: String,
    val platform: String,
    val datetime: String,
    val polarizations: String,
    val orbitState: String,
    val resolutionM: Double?,
    val previewUrl: String?
)

data class TargetImageryBundle(
    val latitude: Double,
    val longitude: Double,
    val halfSizeMeters: Double,
    val opticalDate: String?,
    val sarDate: String?,
    val trueColorRgb: Bitmap?,
    val mineralSwir: Bitmap?,
    val sarBackscatter: Bitmap?
)

data class RadarAnalysisResult(
    val centerLat: Double,
    val centerLon: Double,
    val radiusMeters: Double,
    val matrixSummary: MatrixAnalysisSummary,
    val atmospheric: AtmosphericCorrection,
    val fusionResult: FusionPipelineResult,
    val satellitePasses: List<SatellitePassItem>,
    val cBandCount: Int,
    val lBandCount: Int,
    val s2OpticalCount: Int,
    val demLidarCount: Int,
    val stacWarning: String? = null
)

class RadarRepository {

    private val tileCache = object : LruCache<String, Bitmap>(128) {}

    fun trimMemoryCaches(): Int {
        val count = tileCache.size()
        tileCache.evictAll()
        return count
    }

    private val stacSemaphore = Semaphore(3)
    private val bandSemaphore = Semaphore(4)

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "RadarSurfaceAndroid/1.0 (Geospatial STAC Field Client)")
                .header("Accept", "application/json, image/*, */*")
                .build()
            chain.proceed(req)
        }
        .build()

    private val planetaryApi: PlanetaryComputerApi = Retrofit.Builder()
        .baseUrl("https://planetarycomputer.microsoft.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(PlanetaryComputerApi::class.java)

    private val earthSearchApi: EarthSearchStacApi = Retrofit.Builder()
        .baseUrl("https://earth-search.aws.element84.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(EarthSearchStacApi::class.java)

    private val openMeteoApi: OpenMeteoApi = Retrofit.Builder()
        .baseUrl("https://api.open-meteo.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(OpenMeteoApi::class.java)

    private val overpassApi: OverpassApi = Retrofit.Builder()
        .baseUrl("https://overpass-api.de/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(OverpassApi::class.java)

    companion object {
        fun mapCollectionsForEarthSearch(collections: List<String>): List<String> {
            return collections.map { col ->
                when (col) {
                    "sentinel-1-rtc" -> "sentinel-1-grd"
                    else -> col
                }
            }
        }
    }

    private suspend fun safeStacSearch(
        request: StacSearchRequest,
        errorLog: MutableList<String>? = null
    ): StacFeatureCollection? {
        return stacSemaphore.withPermit {
            // 1. Primary: Microsoft Planetary Computer STAC with transient retry
            var pcResp: StacFeatureCollection? = null
            var lastErr: Throwable? = null
            for (attempt in 0..1) {
                val res = runCatching { planetaryApi.searchCatalog(request) }
                if (res.isSuccess) {
                    pcResp = res.getOrNull()
                    break
                }
                lastErr = res.exceptionOrNull()
                if (attempt == 0) delay(380L)
            }
            if (pcResp != null && pcResp.features.isNotEmpty()) {
                return@withPermit pcResp
            }

            // 2. Relaxed query on Planetary Computer if strict query/datetime returned empty or 4xx
            if (request.query != null || request.datetime != null) {
                val relaxedReq = StacSearchRequest(
                    collections = request.collections,
                    bbox = request.bbox,
                    datetime = null,
                    query = null,
                    limit = maxOf(request.limit, 4)
                )
                val relaxedRes = runCatching { planetaryApi.searchCatalog(relaxedReq) }
                val relaxedBody = relaxedRes.getOrNull()
                if (relaxedBody != null && relaxedBody.features.isNotEmpty()) {
                    return@withPermit relaxedBody
                }
                if (lastErr == null) lastErr = relaxedRes.exceptionOrNull()
            }

            // 3. Secondary Fallback: Element84 Earth Search AWS STAC v1
            val mappedCols = mapCollectionsForEarthSearch(request.collections)
                .filter { it in setOf("sentinel-2-l2a", "sentinel-1-grd", "cop-dem-glo-30", "landsat-c2-l2") }
            if (mappedCols.isNotEmpty()) {
                val esReq = StacSearchRequest(
                    collections = mappedCols,
                    bbox = request.bbox,
                    datetime = null,
                    query = null,
                    limit = maxOf(request.limit, 4)
                )
                val esRes = runCatching { earthSearchApi.searchCatalog(esReq) }
                val esBody = esRes.getOrNull()
                if (esBody != null && esBody.features.isNotEmpty()) {
                    return@withPermit esBody
                }
                if (lastErr == null) lastErr = esRes.exceptionOrNull()
            }

            lastErr?.let { err ->
                val msg = if (err is HttpException) {
                    "HTTP ${err.code()}"
                } else {
                    err.javaClass.simpleName + (err.localizedMessage?.let { ": $it" } ?: "")
                }
                errorLog?.add("${request.collections.joinToString()}: $msg")
            }
            pcResp
        }
    }

    private suspend fun fetchElevationsResilient(
        gridPoints: List<GeoPoint>,
        latQuery: String,
        lonQuery: String
    ): OpenMeteoElevationResponse {
        var lastError: Throwable? = null
        for (attempt in 0..1) {
            val res = runCatching { openMeteoApi.getElevations(latQuery, lonQuery) }
            val body = res.getOrNull()
            if (body != null && body.elevation.size == gridPoints.size) {
                return body
            }
            lastError = res.exceptionOrNull()
            if (attempt == 0) delay(400L)
        }

        // Secondary DEM elevation fallback: OpenTopoData SRTM 30m
        val fallback = runCatching {
            withContext(Dispatchers.IO) {
                val locations = gridPoints.joinToString("|") {
                    String.format(Locale.US, "%.6f,%.6f", it.latitude, it.longitude)
                }
                val url = "https://api.opentopodata.org/v1/srtm30m?locations=$locations"
                val req = Request.Builder().url(url).build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("OpenTopoData HTTP ${resp.code}")
                    val raw = resp.body?.string() ?: error("Empty elevation response")
                    val results = JSONObject(raw).optJSONArray("results") ?: error("Missing results")
                    val elevs = MutableList(results.length()) { idx ->
                        val item = results.getJSONObject(idx)
                        if (item.isNull("elevation")) {
                            0.0
                        } else {
                            val v = item.optDouble("elevation", 0.0)
                            if (v.isNaN() || v.isInfinite()) 0.0 else v
                        }
                    }
                    OpenMeteoElevationResponse(elevs)
                }
            }
        }.getOrNull()

        if (fallback != null && fallback.elevation.size == gridPoints.size) {
            return fallback
        }
        throw IllegalStateException(
            "عدم دسترسی به سرویس ارتفاع (Open-Meteo / OpenTopoData): ${lastError?.localizedMessage ?: "اتصال اینترنت را بررسی کنید"}"
        )
    }

    suspend fun runLiveAnalysis(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double
    ): RadarAnalysisResult = coroutineScope {
        val gridSize = 7
        val clampedRadius = radiusMeters.coerceIn(50.0, 15_000.0)
        val gridPoints = RadarMathEngine.generateSpatialGrid(
            centerLat = centerLat,
            centerLon = centerLon,
            radiusMeters = clampedRadius,
            gridSize = gridSize
        )

        val latQuery = gridPoints.joinToString(",") {
            String.format(Locale.US, "%.6f", it.latitude)
        }
        val lonQuery = gridPoints.joinToString(",") {
            String.format(Locale.US, "%.6f", it.longitude)
        }

        val dLat = (clampedRadius / 111_000.0).coerceAtLeast(0.002)
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
        val dLon = (clampedRadius / (111_000.0 * cosLat)).coerceAtLeast(0.002)
        val minLon = centerLon - dLon
        val minLat = centerLat - dLat
        val maxLon = centerLon + dLon
        val maxLat = centerLat + dLat
        val bbox = listOf(minLon, minLat, maxLon, maxLat)

        val catalogErrors = Collections.synchronizedList(mutableListOf<String>())

        val elevationDeferred = async {
            fetchElevationsResilient(gridPoints, latQuery, lonQuery)
        }
        val atmosphereDeferred = async {
            runCatching {
                openMeteoApi.getCurrentAtmosphere(centerLat, centerLon)
            }.getOrNull()
        }
        val powerInfraDeferred = async {
            fetchOsmPowerInfrastructure(minLat, minLon, maxLat, maxLon)
        }

        val demSearchDeferred = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("cop-dem-glo-30"),
                    bbox = bbox,
                    limit = 1
                ),
                catalogErrors
            )
        }
        val ascSummerDeferred = async {
            val seasonal = safeStacSearch(
                StacSearchRequest(
                    collections = listOf("sentinel-1-rtc"),
                    bbox = bbox,
                    datetime = "2024-06-01T00:00:00Z/2024-09-15T23:59:59Z",
                    query = StacQuery(orbitState = StacEqFilter("ascending")),
                    limit = 1
                ),
                catalogErrors
            )
            if (seasonal?.features?.isNotEmpty() == true) seasonal else {
                safeStacSearch(
                    StacSearchRequest(
                        collections = listOf("sentinel-1-rtc"),
                        bbox = bbox,
                        query = StacQuery(orbitState = StacEqFilter("ascending")),
                        limit = 2
                    ),
                    catalogErrors
                )
            }
        }
        val descSummerDeferred = async {
            val seasonal = safeStacSearch(
                StacSearchRequest(
                    collections = listOf("sentinel-1-rtc"),
                    bbox = bbox,
                    datetime = "2024-06-01T00:00:00Z/2024-09-15T23:59:59Z",
                    query = StacQuery(orbitState = StacEqFilter("descending")),
                    limit = 1
                ),
                catalogErrors
            )
            if (seasonal?.features?.isNotEmpty() == true) seasonal else {
                safeStacSearch(
                    StacSearchRequest(
                        collections = listOf("sentinel-1-rtc"),
                        bbox = bbox,
                        query = StacQuery(orbitState = StacEqFilter("descending")),
                        limit = 2
                    ),
                    catalogErrors
                )
            }
        }
        val ascWinterDeferred = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("sentinel-1-rtc"),
                    bbox = bbox,
                    datetime = "2024-11-15T00:00:00Z/2025-03-15T23:59:59Z",
                    query = StacQuery(orbitState = StacEqFilter("ascending")),
                    limit = 1
                ),
                catalogErrors
            )
        }
        val s2SummerDeferred = async {
            val summer = safeStacSearch(
                StacSearchRequest(
                    collections = listOf("sentinel-2-l2a"),
                    bbox = bbox,
                    datetime = "2024-06-01T00:00:00Z/2024-09-15T23:59:59Z",
                    query = StacQuery(cloudCover = StacLtFilter(15.0)),
                    limit = 1
                ),
                catalogErrors
            )
            if (summer?.features?.isNotEmpty() == true) summer else {
                safeStacSearch(
                    StacSearchRequest(
                        collections = listOf("sentinel-2-l2a"),
                        bbox = bbox,
                        query = StacQuery(cloudCover = StacLtFilter(30.0)),
                        limit = 2
                    ),
                    catalogErrors
                )
            }
        }
        val s2WinterDeferred = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("sentinel-2-l2a"),
                    bbox = bbox,
                    datetime = "2024-11-15T00:00:00Z/2025-03-15T23:59:59Z",
                    query = StacQuery(cloudCover = StacLtFilter(30.0)),
                    limit = 1
                ),
                catalogErrors
            )
        }
        val lstSummerDeferred = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("landsat-c2-l2"),
                    bbox = bbox,
                    datetime = "2024-06-01T00:00:00Z/2024-09-15T23:59:59Z",
                    query = StacQuery(
                        platform = StacInFilter(listOf("landsat-8", "landsat-9")),
                        cloudCover = StacLtFilter(20.0)
                    ),
                    limit = 1
                ),
                catalogErrors
            )
        }
        val lstWinterDeferred = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("landsat-c2-l2"),
                    bbox = bbox,
                    datetime = "2024-11-15T00:00:00Z/2025-03-15T23:59:59Z",
                    query = StacQuery(
                        platform = StacInFilter(listOf("landsat-8", "landsat-9")),
                        cloudCover = StacLtFilter(30.0)
                    ),
                    limit = 1
                ),
                catalogErrors
            )
        }
        val lBandSearchDeferred = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("alos-palsar-mosaic"),
                    bbox = bbox,
                    limit = 2
                ),
                catalogErrors
            )
        }

        val elevationResp = elevationDeferred.await()
        require(elevationResp.elevation.size == gridPoints.size) {
            "پاسخ شبکه ارتفاعی ناقص است (${elevationResp.elevation.size}/${gridPoints.size})."
        }

        val matrixSummary = RadarMathEngine.analyzeSurfaceMatrix(
            gridPoints = gridPoints,
            elevationsM = elevationResp.elevation,
            centerLat = centerLat,
            centerLon = centerLon,
            radiusMeters = clampedRadius,
            gridSize = gridSize
        )

        val currentAtm = atmosphereDeferred.await()?.current
        val atmospheric = RadarMathEngine.computeAtmosphericCorrection(
            pressureHpa = currentAtm?.surfacePressure ?: 1013.25,
            temperatureC = currentAtm?.temperature2m ?: 20.0,
            relativeHumidityPct = currentAtm?.relativeHumidity2m ?: 45.0,
            soilMoistureM3 = currentAtm?.soilMoisture0To1Cm ?: 0.12,
            centerLat = centerLat,
            meanElevationM = matrixSummary.meanElevationM
        )

        val demFeature = demSearchDeferred.await()?.features?.firstOrNull()
        val ascSummerFeat = ascSummerDeferred.await()?.features?.firstOrNull()
        val descSummerFeat = descSummerDeferred.await()?.features?.firstOrNull()
        val ascWinterFeat = ascWinterDeferred.await()?.features?.firstOrNull()
        val s2SummerFeat = s2SummerDeferred.await()?.features?.firstOrNull()
        val s2WinterFeat = s2WinterDeferred.await()?.features?.firstOrNull()
        val lstSummerFeat = lstSummerDeferred.await()?.features?.firstOrNull()
        val lstWinterFeat = lstWinterDeferred.await()?.features?.firstOrNull()
        val lBandFeatures = lBandSearchDeferred.await()?.features.orEmpty()
        val lBandFirst = lBandFeatures.firstOrNull()

        val rasterDim = 161
        val minE = floor((elevationResp.elevation.minOrNull() ?: 0.0) - 30.0)
        val maxE = ceil((elevationResp.elevation.maxOrNull() ?: 2000.0) + 30.0)

        val demRasterDef = async {
            demFeature?.let {
                fetchScaledBand("cop-dem-glo-30", it.id, "data", bbox, rasterDim, minE, maxE)
            }
        }
        val ascCoDef = async {
            ascSummerFeat?.let { feat ->
                val band = if (feat.assets?.containsKey("hh") == true) "hh" else "vv"
                fetchScaledBand("sentinel-1-rtc", feat.id, band, bbox, rasterDim, 0.0, 1.2)
            }
        }
        val ascCrossDef = async {
            ascSummerFeat?.let { feat ->
                val band = when {
                    feat.assets?.containsKey("vh") == true -> "vh"
                    feat.assets?.containsKey("hv") == true -> "hv"
                    else -> null
                }
                band?.let { fetchScaledBand("sentinel-1-rtc", feat.id, it, bbox, rasterDim, 0.0, 0.40) }
            }
        }
        val descCoDef = async {
            descSummerFeat?.let { feat ->
                val band = if (feat.assets?.containsKey("hh") == true) "hh" else "vv"
                fetchScaledBand("sentinel-1-rtc", feat.id, band, bbox, rasterDim, 0.0, 1.2)
            }
        }
        val descCrossDef = async {
            descSummerFeat?.let { feat ->
                val band = when {
                    feat.assets?.containsKey("vh") == true -> "vh"
                    feat.assets?.containsKey("hv") == true -> "hv"
                    else -> null
                }
                band?.let { fetchScaledBand("sentinel-1-rtc", feat.id, it, bbox, rasterDim, 0.0, 0.40) }
            }
        }
        val ascWinterCoDef = async {
            ascWinterFeat?.let { feat ->
                val band = if (feat.assets?.containsKey("hh") == true) "hh" else "vv"
                fetchScaledBand("sentinel-1-rtc", feat.id, band, bbox, rasterDim, 0.0, 1.2)
            }
        }
        val lBandHhDef = async {
            lBandFirst?.takeIf { it.assets?.containsKey("HH") == true }?.let {
                fetchScaledBand("alos-palsar-mosaic", it.id, "HH", bbox, rasterDim, 0.0, 16000.0)
            }
        }
        val lBandHvDef = async {
            lBandFirst?.takeIf { it.assets?.containsKey("HV") == true }?.let {
                fetchScaledBand("alos-palsar-mosaic", it.id, "HV", bbox, rasterDim, 0.0, 16000.0)
            }
        }

        val s2BlueDef = async { s2SummerFeat?.let { fetchScaledBand("sentinel-2-l2a", it.id, "B02", bbox, rasterDim, 0.0, 8000.0, 0.8) } }
        val s2GreenDef = async { s2SummerFeat?.let { fetchScaledBand("sentinel-2-l2a", it.id, "B03", bbox, rasterDim, 0.0, 8000.0, 0.8) } }
        val s2RedDef = async { s2SummerFeat?.let { fetchScaledBand("sentinel-2-l2a", it.id, "B04", bbox, rasterDim, 0.0, 8000.0, 0.8) } }
        val s2NirDef = async { s2SummerFeat?.let { fetchScaledBand("sentinel-2-l2a", it.id, "B08", bbox, rasterDim, 0.0, 8000.0, 0.8) } }
        val s2Swir1Def = async { s2SummerFeat?.let { fetchScaledBand("sentinel-2-l2a", it.id, "B11", bbox, rasterDim, 0.0, 8000.0, 0.8) } }
        val s2Swir2Def = async { s2SummerFeat?.let { fetchScaledBand("sentinel-2-l2a", it.id, "B12", bbox, rasterDim, 0.0, 8000.0, 0.8) } }
        val s2SclDef = async { s2SummerFeat?.let { fetchRawIntBand("sentinel-2-l2a", it.id, "SCL", bbox, rasterDim) } }

        val s2WinSw1Def = async { s2WinterFeat?.let { fetchScaledBand("sentinel-2-l2a", it.id, "B11", bbox, rasterDim, 0.0, 8000.0, 0.8) } }
        val s2WinSw2Def = async { s2WinterFeat?.let { fetchScaledBand("sentinel-2-l2a", it.id, "B12", bbox, rasterDim, 0.0, 8000.0, 0.8) } }
        val s2WinSclDef = async { s2WinterFeat?.let { fetchRawIntBand("sentinel-2-l2a", it.id, "SCL", bbox, rasterDim) } }

        val lstSumDef = async { lstSummerFeat?.let { fetchLandsatKelvin("landsat-c2-l2", it.id, bbox, rasterDim) } }
        val lstWinDef = async { lstWinterFeat?.let { fetchLandsatKelvin("landsat-c2-l2", it.id, bbox, rasterDim) } }

        val demRaster = demRasterDef.await() ?: interpolateOpenMeteoToGrid(
            elevations7x7 = elevationResp.elevation,
            srcSize = gridSize,
            dstSize = rasterDim
        )

        val polSet = buildSet {
            ascSummerFeat?.properties?.polarizations?.forEach { add("C:$it") }
            descSummerFeat?.properties?.polarizations?.forEach { add("C:$it") }
            if (lBandFirst != null) {
                if (lBandFirst.assets?.containsKey("HH") == true) add("L:HH")
                if (lBandFirst.assets?.containsKey("HV") == true) add("L:HV")
            }
        }
        val polLabel = if (polSet.isEmpty()) "C:VV/VH" else polSet.joinToString(", ")

        val fusionResult = SarOpticalFusionEngine.runPipeline(
            width = rasterDim,
            height = rasterDim,
            minLon = minLon,
            minLat = minLat,
            maxLon = maxLon,
            maxLat = maxLat,
            centerLat = centerLat,
            centerLon = centerLon,
            demElevations = demRaster,
            sarAscendingVv = ascCoDef.await(),
            sarAscendingVh = ascCrossDef.await(),
            sarDescendingVv = descCoDef.await(),
            sarDescendingVh = descCrossDef.await(),
            sarWinterVv = ascWinterCoDef.await(),
            sarLBandHh = lBandHhDef.await(),
            sarLBandHv = lBandHvDef.await(),
            availablePolLabels = polLabel,
            s2SummerBlue = s2BlueDef.await(),
            s2SummerGreen = s2GreenDef.await(),
            s2SummerRed = s2RedDef.await(),
            s2SummerNir = s2NirDef.await(),
            s2SummerSwir1 = s2Swir1Def.await(),
            s2SummerSwir2 = s2Swir2Def.await(),
            s2SummerScl = s2SclDef.await(),
            s2WinterSwir1 = s2WinSw1Def.await(),
            s2WinterSwir2 = s2WinSw2Def.await(),
            s2WinterScl = s2WinSclDef.await(),
            lstSummerK = lstSumDef.await(),
            lstWinterK = lstWinDef.await(),
            powerInfraPoints = powerInfraDeferred.await()
        )

        val rawFeatures = listOfNotNull(
            ascSummerFeat,
            descSummerFeat,
            ascWinterFeat,
            s2SummerFeat,
            s2WinterFeat,
            lstSummerFeat,
            lstWinterFeat,
            demFeature
        ) + lBandFeatures
        val passes = rawFeatures.map { mapStacFeature(it) }
        val stacWarning = if (passes.isEmpty()) {
            val diag = catalogErrors.firstOrNull()
            if (diag != null) {
                "اتصال به کاتالوگ ماهواره‌ای مایکروسافت / Earth Search برقرار نشد ($diag). اینترنت/فیلترشکن را بررسی کنید؛ تحلیل ماتریس ارتفاعی DEM برقرار است."
            } else {
                "کاتالوگ ماهواره‌ای مایکروسافت و پشتیبان برای این محدوده صحنه‌ای برنگرداند."
            }
        } else null

        RadarAnalysisResult(
            centerLat = centerLat,
            centerLon = centerLon,
            radiusMeters = clampedRadius,
            matrixSummary = matrixSummary,
            atmospheric = atmospheric,
            fusionResult = fusionResult,
            satellitePasses = passes,
            cBandCount = listOfNotNull(ascSummerFeat, descSummerFeat, ascWinterFeat).size,
            lBandCount = lBandFeatures.size,
            s2OpticalCount = listOfNotNull(s2SummerFeat, s2WinterFeat).size,
            demLidarCount = if (demFeature != null) 1 else 0,
            stacWarning = stacWarning
        )
    }

    /**
     * Fetches two sets of Sentinel-1 RTC SAR images from two distinct seasons (Summer & Winter),
     * coregisters them at sub-pixel accuracy, performs pixel-wise subtraction, and extracts
     * persistent structural signatures while suppressing transient surface noise.
     */
    suspend fun fetchAndAnalyzeTwoSeasonSar(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        summerDatetime: String = "2024-06-01T00:00:00Z/2024-09-15T23:59:59Z",
        winterDatetime: String = "2024-11-15T00:00:00Z/2025-03-15T23:59:59Z"
    ): com.example.domain.TwoSeasonSarSubtractionResult? = coroutineScope {
        val clampedRadius = radiusMeters.coerceIn(50.0, 15_000.0)
        val dLat = (clampedRadius / 111_000.0).coerceAtLeast(0.002)
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
        val dLon = (clampedRadius / (111_000.0 * cosLat)).coerceAtLeast(0.002)
        val minLon = centerLon - dLon
        val minLat = centerLat - dLat
        val maxLon = centerLon + dLon
        val maxLat = centerLat + dLat
        val bbox = listOf(minLon, minLat, maxLon, maxLat)

        val summerDef = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("sentinel-1-rtc"),
                    bbox = bbox,
                    datetime = summerDatetime,
                    query = StacQuery(orbitState = StacEqFilter("ascending")),
                    limit = 1
                )
            )?.features?.firstOrNull()
        }
        val winterDef = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("sentinel-1-rtc"),
                    bbox = bbox,
                    datetime = winterDatetime,
                    query = StacQuery(orbitState = StacEqFilter("ascending")),
                    limit = 1
                )
            )?.features?.firstOrNull()
        }

        val summerItem = summerDef.await() ?: return@coroutineScope null
        val winterItem = winterDef.await() ?: return@coroutineScope null

        val rasterDim = 81
        val sumBand = if (summerItem.assets?.containsKey("hh") == true) "hh" else "vv"
        val winBand = if (winterItem.assets?.containsKey("hh") == true) "hh" else "vv"

        val sumRasterDef = async {
            fetchScaledBand("sentinel-1-rtc", summerItem.id, sumBand, bbox, rasterDim, 0.0, 0.45)
        }
        val winRasterDef = async {
            fetchScaledBand("sentinel-1-rtc", winterItem.id, winBand, bbox, rasterDim, 0.0, 0.45)
        }

        val sumRaster = sumRasterDef.await() ?: return@coroutineScope null
        val winRaster = winRasterDef.await() ?: return@coroutineScope null

        SarOpticalFusionEngine.analyzeTwoSeasonSarDifference(
            summerSar = sumRaster,
            winterSar = winRaster,
            width = rasterDim,
            height = rasterDim,
            minLon = minLon,
            minLat = minLat,
            maxLon = maxLon,
            maxLat = maxLat,
            centerLat = centerLat,
            centerLon = centerLon
        )
    }

    private suspend fun fetchOsmPowerInfrastructure(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double
    ): List<GeoPoint> {
        val q = String.format(
            Locale.US,
            "[out:json][timeout:8];(node[\"power\"~\"tower|pole\"](%.6f,%.6f,%.6f,%.6f);way[\"power\"~\"line|minor_line\"](%.6f,%.6f,%.6f,%.6f););out geom;",
            minLat, minLon, maxLat, maxLon,
            minLat, minLon, maxLat, maxLon
        )
        return runCatching {
            val resp = overpassApi.queryPowerInfrastructure(q)
            val pts = mutableListOf<GeoPoint>()
            resp.elements.forEach { el ->
                if (el.lat != null && el.lon != null) {
                    pts.add(GeoPoint(el.lat, el.lon))
                }
                val geom = el.geometry
                if (!geom.isNullOrEmpty()) {
                    for (idx in geom.indices) {
                        val a = geom[idx]
                        pts.add(GeoPoint(a.lat, a.lon))
                        if (idx + 1 < geom.size) {
                            val b = geom[idx + 1]
                            for (step in 1..4) {
                                val t = step / 5.0
                                pts.add(
                                    GeoPoint(
                                        a.lat + (b.lat - a.lat) * t,
                                        a.lon + (b.lon - a.lon) * t
                                    )
                                )
                            }
                        }
                    }
                }
            }
            pts
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchLandsatKelvin(
        collection: String,
        itemId: String,
        bbox: List<Double>,
        dim: Int
    ): FloatArray? {
        val rawScaled = fetchScaledBand(collection, itemId, "lwir11", bbox, dim, 35000.0, 52000.0) ?: return null
        return FloatArray(rawScaled.size) { i ->
            (rawScaled[i] * 0.00341802f + 149.0f)
        }
    }

    private suspend fun fetchScaledBand(
        collection: String,
        itemId: String,
        asset: String,
        bbox: List<Double>,
        dim: Int,
        rescaleMin: Double,
        rescaleMax: Double,
        outputMultiplier: Double? = null
    ): FloatArray? = withContext(Dispatchers.IO) {
        val base = String.format(
            Locale.US,
            "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/%.6f,%.6f,%.6f,%.6f/%dx%d.png",
            bbox[0], bbox[1], bbox[2], bbox[3],
            dim, dim
        )
        val builtUrl = base.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("collection", collection)
            ?.addQueryParameter("item", itemId)
            ?.addQueryParameter("assets", asset)
            ?.addQueryParameter("rescale", String.format(Locale.US, "%.4f,%.4f", rescaleMin, rescaleMax))
            ?.build()
            ?.toString()
            ?: return@withContext null

        bandSemaphore.withPermit {
            for (attempt in 0..1) {
                val decoded = runCatching {
                    val req = Request.Builder().url(builtUrl).build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        val rawBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
                        val bmp = if (rawBmp.width != dim || rawBmp.height != dim) {
                            Bitmap.createScaledBitmap(rawBmp, dim, dim, true).also { rawBmp.recycle() }
                        } else rawBmp
                        val pixels = IntArray(dim * dim)
                        bmp.getPixels(pixels, 0, dim, 0, 0, dim, dim)
                        bmp.recycle()
                        val out = FloatArray(pixels.size)
                        val span = rescaleMax - rescaleMin
                        for (i in pixels.indices) {
                            val gray = Color.red(pixels[i]) / 255.0
                            out[i] = if (outputMultiplier != null) {
                                (gray * outputMultiplier).toFloat()
                            } else {
                                (rescaleMin + gray * span).toFloat()
                            }
                        }
                        out
                    }
                }.getOrNull()
                if (decoded != null) return@withPermit decoded
                if (attempt == 0) delay(280L)
            }
            null
        }
    }

    private suspend fun fetchRawIntBand(
        collection: String,
        itemId: String,
        asset: String,
        bbox: List<Double>,
        dim: Int
    ): IntArray? = withContext(Dispatchers.IO) {
        val base = String.format(
            Locale.US,
            "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/%.6f,%.6f,%.6f,%.6f/%dx%d.png",
            bbox[0], bbox[1], bbox[2], bbox[3],
            dim, dim
        )
        val builtUrl = base.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("collection", collection)
            ?.addQueryParameter("item", itemId)
            ?.addQueryParameter("assets", asset)
            ?.addQueryParameter("rescale", "0,255")
            ?.addQueryParameter("resampling", "nearest")
            ?.build()
            ?.toString()
            ?: return@withContext null

        bandSemaphore.withPermit {
            for (attempt in 0..1) {
                val decoded = runCatching {
                    val req = Request.Builder().url(builtUrl).build()
                    okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        val rawBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
                        val bmp = if (rawBmp.width != dim || rawBmp.height != dim) {
                            Bitmap.createScaledBitmap(rawBmp, dim, dim, false).also { rawBmp.recycle() }
                        } else rawBmp
                        val pixels = IntArray(dim * dim)
                        bmp.getPixels(pixels, 0, dim, 0, 0, dim, dim)
                        bmp.recycle()
                        IntArray(pixels.size) { i -> Color.red(pixels[i]) }
                    }
                }.getOrNull()
                if (decoded != null) return@withPermit decoded
                if (attempt == 0) delay(280L)
            }
            null
        }
    }

    private fun interpolateOpenMeteoToGrid(
        elevations7x7: List<Double>,
        srcSize: Int,
        dstSize: Int
    ): FloatArray {
        val out = FloatArray(dstSize * dstSize)
        for (r in 0 until dstSize) {
            val gy = (r.toDouble() / (dstSize - 1)) * (srcSize - 1)
            val y0 = floor(gy).toInt().coerceIn(0, srcSize - 2)
            val fy = gy - y0
            for (c in 0 until dstSize) {
                val gx = (c.toDouble() / (dstSize - 1)) * (srcSize - 1)
                val x0 = floor(gx).toInt().coerceIn(0, srcSize - 2)
                val fx = gx - x0
                val v00 = elevations7x7[y0 * srcSize + x0]
                val v10 = elevations7x7[y0 * srcSize + x0 + 1]
                val v01 = elevations7x7[(y0 + 1) * srcSize + x0]
                val v11 = elevations7x7[(y0 + 1) * srcSize + x0 + 1]
                val top = v00 * (1.0 - fx) + v10 * fx
                val bot = v01 * (1.0 - fx) + v11 * fx
                out[r * dstSize + c] = (top * (1.0 - fy) + bot * fy).toFloat()
            }
        }
        return out
    }

    private fun mapStacFeature(feature: StacFeature): SatellitePassItem {
        val col = feature.collection ?: "unknown"
        val props = feature.properties
        val band = when {
            col.contains("sentinel-1", ignoreCase = true) -> {
                val orb = if (props?.orbitState == "ascending") "رفت شرق (Asc)" else if (props?.orbitState == "descending") "برگشت غرب (Desc)" else ""
                "باند C رادار RTC $orb"
            }
            col.contains("sentinel-2", ignoreCase = true) -> "چندطیفی Sentinel-2 (ابر ${props?.cloudCover?.toInt() ?: 0}%)"
            col.contains("landsat", ignoreCase = true) -> "حرارتی Landsat LST (ابر ${props?.cloudCover?.toInt() ?: 0}%)"
            col.contains("alos-palsar", ignoreCase = true) -> "باند L (ALOS PALSAR HH/HV)"
            col.contains("cop-dem", ignoreCase = true) -> "مدل ارتفاعی Copernicus DEM 30m"
            else -> props?.frequencyBand ?: col
        }
        val preview = feature.assets?.get("rendered_preview")?.href
            ?: feature.assets?.get("thumbnail")?.href

        return SatellitePassItem(
            id = feature.id,
            collection = col,
            bandLabel = band,
            platform = props?.platform ?: props?.constellation ?: col,
            datetime = props?.datetime?.take(19)?.replace('T', ' ') ?: "نامشخص",
            polarizations = props?.polarizations?.joinToString(" / ") ?: "-",
            orbitState = props?.orbitState ?: "-",
            resolutionM = props?.gsd,
            previewUrl = preview
        )
    }

    suspend fun fetchMapTile(url: String): Bitmap? = withContext(Dispatchers.IO) {
        tileCache.get(url)?.let { return@withContext it }
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "RadarSurfaceAndroid/1.0 (Geospatial Field Navigation)")
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use null
                tileCache.put(url, bmp)
                bmp
            }
        }.getOrNull()
    }

    suspend fun fetchTargetSatelliteImagery(
        targetLat: Double,
        targetLon: Double,
        halfSizeMeters: Double = 180.0
    ): TargetImageryBundle = coroutineScope {
        val dLat = (halfSizeMeters / 111_000.0).coerceAtLeast(0.0012)
        val cosLat = cos(Math.toRadians(targetLat)).coerceAtLeast(0.1)
        val dLon = (halfSizeMeters / (111_000.0 * cosLat)).coerceAtLeast(0.0012)
        val minLon = targetLon - dLon
        val minLat = targetLat - dLat
        val maxLon = targetLon + dLon
        val maxLat = targetLat + dLat
        val bbox = listOf(minLon, minLat, maxLon, maxLat)

        val s2Def = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("sentinel-2-l2a"),
                    bbox = bbox,
                    query = StacQuery(cloudCover = StacLtFilter(25.0)),
                    limit = 1
                )
            )?.features?.firstOrNull()
        }
        val s1Def = async {
            safeStacSearch(
                StacSearchRequest(
                    collections = listOf("sentinel-1-rtc"),
                    bbox = bbox,
                    limit = 1
                )
            )?.features?.firstOrNull()
        }

        val s2Item = s2Def.await()
        val s1Item = s1Def.await()

        val rgbDef = async {
            s2Item?.let { feat ->
                val url = if (feat.assets?.containsKey("visual") == true) {
                    String.format(
                        Locale.US,
                        "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/%.6f,%.6f,%.6f,%.6f/384x384.png?collection=sentinel-2-l2a&item=%s&assets=visual",
                        minLon, minLat, maxLon, maxLat, feat.id
                    )
                } else {
                    String.format(
                        Locale.US,
                        "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/%.6f,%.6f,%.6f,%.6f/384x384.png?collection=sentinel-2-l2a&item=%s&assets=B04&assets=B03&assets=B02&rescale=0,3000",
                        minLon, minLat, maxLon, maxLat, feat.id
                    )
                }
                fetchBitmapDirect(url)
                    ?: feat.assets?.get("rendered_preview")?.href?.let { fetchBitmapDirect(it) }
                    ?: feat.assets?.get("thumbnail")?.href?.let { fetchBitmapDirect(it) }
            }
        }
        val swirDef = async {
            s2Item?.let { feat ->
                val url = String.format(
                    Locale.US,
                    "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/%.6f,%.6f,%.6f,%.6f/384x384.png?collection=sentinel-2-l2a&item=%s&assets=B12&assets=B11&assets=B04&rescale=0,4500",
                    minLon, minLat, maxLon, maxLat, feat.id
                )
                fetchBitmapDirect(url)
                    ?: feat.assets?.get("rendered_preview")?.href?.let { fetchBitmapDirect(it) }
            }
        }
        val sarDef = async {
            s1Item?.let { feat ->
                val col = feat.collection ?: "sentinel-1-rtc"
                val band = if (feat.assets?.containsKey("hh") == true) "hh" else "vv"
                val url = String.format(
                    Locale.US,
                    "https://planetarycomputer.microsoft.com/api/data/v1/item/bbox/%.6f,%.6f,%.6f,%.6f/384x384.png?collection=%s&item=%s&assets=%s&rescale=0.0,0.45",
                    minLon, minLat, maxLon, maxLat, col, feat.id, band
                )
                fetchBitmapDirect(url)
                    ?: feat.assets?.get("rendered_preview")?.href?.let { fetchBitmapDirect(it) }
                    ?: feat.assets?.get("thumbnail")?.href?.let { fetchBitmapDirect(it) }
            }
        }

        TargetImageryBundle(
            latitude = targetLat,
            longitude = targetLon,
            halfSizeMeters = halfSizeMeters,
            opticalDate = s2Item?.properties?.datetime?.take(10),
            sarDate = s1Item?.properties?.datetime?.take(10),
            trueColorRgb = rgbDef.await(),
            mineralSwir = swirDef.await(),
            sarBackscatter = sarDef.await()
        )
    }

    private suspend fun fetchBitmapDirect(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }
}
