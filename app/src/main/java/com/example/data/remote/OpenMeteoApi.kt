package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class OpenMeteoElevationResponse(
    @Json(name = "elevation") val elevation: List<Double> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OpenMeteoForecastResponse(
    @Json(name = "current") val current: OpenMeteoCurrent? = null
)

@JsonClass(generateAdapter = true)
data class OpenMeteoCurrent(
    @Json(name = "time") val time: String? = null,
    @Json(name = "temperature_2m") val temperature2m: Double? = null,
    @Json(name = "relative_humidity_2m") val relativeHumidity2m: Double? = null,
    @Json(name = "surface_pressure") val surfacePressure: Double? = null,
    @Json(name = "soil_moisture_0_to_1cm") val soilMoisture0To1Cm: Double? = null
)

interface OpenMeteoApi {
    @GET("v1/elevation")
    suspend fun getElevations(
        @Query("latitude") latitudes: String,
        @Query("longitude") longitudes: String
    ): OpenMeteoElevationResponse

    @GET("v1/forecast")
    suspend fun getCurrentAtmosphere(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,surface_pressure,soil_moisture_0_to_1cm"
    ): OpenMeteoForecastResponse
}
