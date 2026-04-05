package com.roadwise.routing

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

import retrofit2.http.GET
import retrofit2.http.Query

interface OpenRouteServiceApi {
    @POST("v2/directions/driving-car/geojson")
    suspend fun getDirections(
        @Header("Authorization") apiKey: String,
        @Body request: RoutingRequest
    ): RoutingResponse

}
