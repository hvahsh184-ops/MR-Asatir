package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

@JsonClass(generateAdapter = true)
data class OverpassResponse(
    @Json(name = "elements") val elements: List<OverpassElement> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OverpassElement(
    @Json(name = "type") val type: String = "",
    @Json(name = "id") val id: Long = 0L,
    @Json(name = "lat") val lat: Double? = null,
    @Json(name = "lon") val lon: Double? = null,
    @Json(name = "geometry") val geometry: List<OverpassLatLon>? = null
)

@JsonClass(generateAdapter = true)
data class OverpassLatLon(
    @Json(name = "lat") val lat: Double,
    @Json(name = "lon") val lon: Double
)

interface OverpassApi {
    @FormUrlEncoded
    @POST("api/interpreter")
    suspend fun queryPowerInfrastructure(
        @Field("data") query: String
    ): OverpassResponse
}
