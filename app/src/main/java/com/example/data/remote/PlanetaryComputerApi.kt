package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

@JsonClass(generateAdapter = true)
data class StacEqFilter(
    @Json(name = "eq") val eq: String
)

@JsonClass(generateAdapter = true)
data class StacLtFilter(
    @Json(name = "lt") val lt: Double
)

@JsonClass(generateAdapter = true)
data class StacInFilter(
    @Json(name = "in") val values: List<String>
)

@JsonClass(generateAdapter = true)
data class StacQuery(
    @Json(name = "sat:orbit_state") val orbitState: StacEqFilter? = null,
    @Json(name = "eo:cloud_cover") val cloudCover: StacLtFilter? = null,
    @Json(name = "platform") val platform: StacInFilter? = null
)

@JsonClass(generateAdapter = true)
data class StacSearchRequest(
    @Json(name = "collections") val collections: List<String>,
    @Json(name = "bbox") val bbox: List<Double>,
    @Json(name = "datetime") val datetime: String? = null,
    @Json(name = "query") val query: StacQuery? = null,
    @Json(name = "limit") val limit: Int = 12
)

@JsonClass(generateAdapter = true)
data class StacFeatureCollection(
    @Json(name = "features") val features: List<StacFeature> = emptyList()
)

@JsonClass(generateAdapter = true)
data class StacFeature(
    @Json(name = "id") val id: String = "",
    @Json(name = "collection") val collection: String? = null,
    @Json(name = "bbox") val bbox: List<Double>? = null,
    @Json(name = "properties") val properties: StacProperties? = null,
    @Json(name = "assets") val assets: Map<String, StacAsset>? = null
)

@JsonClass(generateAdapter = true)
data class StacProperties(
    @Json(name = "datetime") val datetime: String? = null,
    @Json(name = "platform") val platform: String? = null,
    @Json(name = "constellation") val constellation: String? = null,
    @Json(name = "sar:frequency_band") val frequencyBand: String? = null,
    @Json(name = "sar:polarizations") val polarizations: List<String>? = null,
    @Json(name = "sar:instrument_mode") val instrumentMode: String? = null,
    @Json(name = "sat:orbit_state") val orbitState: String? = null,
    @Json(name = "eo:cloud_cover") val cloudCover: Double? = null,
    @Json(name = "gsd") val gsd: Double? = null
)

@JsonClass(generateAdapter = true)
data class StacAsset(
    @Json(name = "href") val href: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "type") val type: String? = null
)

interface PlanetaryComputerApi {
    @POST("api/stac/v1/search")
    suspend fun searchCatalog(@Body request: StacSearchRequest): StacFeatureCollection
}

interface EarthSearchStacApi {
    @POST("v1/search")
    suspend fun searchCatalog(@Body request: StacSearchRequest): StacFeatureCollection
}
