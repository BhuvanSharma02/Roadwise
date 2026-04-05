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
        
        val options = if (potholesToAvoid.isNotEmpty()) {
            val polygons = potholesToAvoid.map { data ->
                listOf(BoundingBoxUtils.getPotholePolygon(data.location, 20.0)) // 20m avoidance zone
            }
            RoutingOptions(avoidPolygons = AvoidPolygons(coordinates = polygons))
        } else {
            null
        }
        
        // Request up to 3 alternatives
        val alternatives = AlternativeRoutes(targetCount = 3)
        val request = RoutingRequest(coordinates, options, alternatives)
        
        try {
            val response = api.getDirections(BuildConfig.ORS_API_KEY, request)
            
            return response.features.mapNotNull { feature ->
                if (feature.geometry.type == "LineString") {
                    val points = feature.geometry.coordinates.map { GeoPoint(it[1], it[0]) }
                    val distance = feature.properties?.summary?.distance ?: 0.0
                    RouteResult(points, distance)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("RoutingManager", "Error fetching route", e)
            throw e
        }
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
