package com.roadwise.routing

import retrofit2.http.GET
import retrofit2.http.Query

interface PhotonApi {
    @GET("api/")
    suspend fun searchPlace(
        @Query("q") query: String,
        @Query("lat") lat: Double? = null,
        @Query("lon") lon: Double? = null,
        @Query("limit") limit: Int = 10,
        @Query("bbox") bbox: String? = null
    ): PhotonResponse
}
