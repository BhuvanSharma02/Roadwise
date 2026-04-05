package com.roadwise.routing

import com.google.gson.annotations.SerializedName

data class RoutingRequest(
    val coordinates: List<List<Double>>, // [[lon1, lat1], [lon2, lat2]]
    val options: RoutingOptions? = null,
    @SerializedName("alternative_routes")
    val alternativeRoutes: AlternativeRoutes? = null
)

data class AlternativeRoutes(
    @SerializedName("target_count")
    val targetCount: Int = 3,
    @SerializedName("weight_factor")
    val weightFactor: Double = 1.4,
    @SerializedName("share_factor")
    val shareFactor: Double = 0.6
)

data class RoutingOptions(
    @SerializedName("avoid_polygons")
    val avoidPolygons: AvoidPolygons? = null
)

data class AvoidPolygons(
    val type: String = "MultiPolygon",
    val coordinates: List<List<List<List<Double>>>>
)

data class RoutingResponse(
    val type: String?,
    val features: List<Feature>
)

data class Feature(
    val geometry: Geometry,
    val properties: Properties?
)

data class Geometry(
    val type: String,
    val coordinates: List<List<Double>> // Only for LineString
)

data class Properties(
    val summary: Summary?
)

data class Summary(
    val distance: Double,
    val duration: Double
)

// Photon Geocoding Models
data class PhotonResponse(
    val features: List<PhotonFeature>
)

data class PhotonFeature(
    val geometry: PhotonGeometry,
    val properties: PhotonProperties
)

data class PhotonGeometry(
    val coordinates: List<Double> // [lon, lat]
)

data class PhotonProperties(
    val name: String?,
    val street: String?,
    val city: String?,
    val state: String?,
    val country: String?
)

// Internal App Model to bundle route logic
data class RouteResult(
    val points: List<org.osmdroid.util.GeoPoint>,
    val distanceMeters: Double
)
