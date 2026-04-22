package com.roadwise.routing

import android.util.Log
import com.roadwise.BuildConfig
import com.roadwise.models.PotholeData
import org.osmdroid.util.GeoPoint
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

class RoutingManager {
    private val api: OpenRouteServiceApi
    private val photonApi: PhotonApi

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.openrouteservice.org/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            
        api = retrofit.create(OpenRouteServiceApi::class.java)

        val nominatimClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "RoadWiseApp/1.0 (test_nominatim_integration)")
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(logging)
            .build()
            
        val photonRetrofit = Retrofit.Builder()
            .baseUrl("https://photon.komoot.io/")
            .client(nominatimClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        photonApi = photonRetrofit.create(PhotonApi::class.java)
    }
    
    suspend fun getRoute(
        start: GeoPoint,
        end: GeoPoint,
        potholesToAvoid: List<PotholeData>
    ): List<RouteResult> {
        if (BuildConfig.ORS_API_KEY.isEmpty()) {
            throw IllegalStateException("API Key not found. Please add ORS_API_KEY to local.properties")
        }

        val coordinates = listOf(
            listOf(start.longitude, start.latitude),
            listOf(end.longitude, end.latitude)
        )
        
        val results = mutableListOf<RouteResult>()
        
        // 1. Get Direct Route (No avoidance)
        try {
            val directRequest = RoutingRequest(coordinates, null, null)
            val directResponse = api.getDirections(BuildConfig.ORS_API_KEY, directRequest)
            directResponse.features.firstOrNull()?.let { feature ->
                val points = feature.geometry.coordinates.map { GeoPoint(it[1], it[0]) }
                val distance = feature.properties?.summary?.distance ?: 0.0
                results.add(RouteResult(points, distance))
            }
        } catch (e: Exception) { Log.e("RoutingManager", "Direct route failed", e) }

        // 2. Get Safe Routes (With avoidance)
        if (potholesToAvoid.isNotEmpty()) {
            val polygons = potholesToAvoid.map { data ->
                listOf(BoundingBoxUtils.getPotholePolygon(data.location, 25.0))
            }
            val options = RoutingOptions(avoidPolygons = AvoidPolygons(coordinates = polygons))
            val alternatives = AlternativeRoutes(targetCount = 2)
            val safeRequest = RoutingRequest(coordinates, options, alternatives)
            
            try {
                val safeResponse = api.getDirections(BuildConfig.ORS_API_KEY, safeRequest)
                safeResponse.features.forEach { feature ->
                    val points = feature.geometry.coordinates.map { GeoPoint(it[1], it[0]) }
                    val distance = feature.properties?.summary?.distance ?: 0.0
                    // Avoid adding duplicate of direct route if it's the same
                    if (results.none { it.points.size == points.size && it.distanceMeters == distance }) {
                        results.add(RouteResult(points, distance))
                    }
                }
            } catch (e: Exception) { Log.e("RoutingManager", "Safe routes failed", e) }
        }
        
        return results
    }

    suspend fun searchPlaces(query: String, lat: Double? = null, lon: Double? = null): List<PhotonFeature> {
        return try {
            // Hard bounding box physically boxing India (minLon, minLat, maxLon, maxLat)
            val indiaBbox = "68.1,6.7,97.4,35.5"
            val response = photonApi.searchPlace(query, lat, lon, bbox = indiaBbox)
            response.features
        } catch (e: Exception) {
            Log.e("RoutingManager", "Error fetching search results", e)
            emptyList()
        }
    }
}
