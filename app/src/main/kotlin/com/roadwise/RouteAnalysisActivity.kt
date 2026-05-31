package com.roadwise

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import com.roadwise.databinding.ActivityRouteAnalysisBinding
import com.roadwise.models.PotholeData
import com.roadwise.routing.RouteResult
import com.roadwise.sensors.RoadFeature
import com.roadwise.utils.PotholeRepository
import com.roadwise.utils.Severity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker
import java.util.*

class RouteAnalysisActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRouteAnalysisBinding
    private lateinit var map: MapView
    private var allRoutes: List<RouteResult> = emptyList()
    private val routeOverlays = mutableListOf<Polyline>()
    private val hazardMarkers = mutableListOf<Marker>()
    private var selectedRouteIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        binding = ActivityRouteAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        map = binding.previewMap
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        allRoutes = intent.getParcelableArrayListExtra<RouteResult>("ALL_ROUTES") ?: emptyList()
        val initialIndex = intent.getIntExtra("SELECTED_INDEX", 0)

        if (allRoutes.isNotEmpty()) {
            setupRoutes(initialIndex)
            updateStatsForRoute(allRoutes[initialIndex.coerceIn(allRoutes.indices)], initialIndex == 0)
        }

        binding.btnAnalysisBack.setOnClickListener { finish() }
        binding.btnStartDrive.setOnClickListener {
            val intent = Intent().apply {
                putExtra("SELECTED_ROUTE", allRoutes[selectedRouteIndex])
            }
            setResult(android.app.Activity.RESULT_OK, intent)
            finish()
        }
    }

    private fun setupRoutes(selectedIndex: Int) {
        selectedRouteIndex = selectedIndex
        routeOverlays.forEach { map.overlays.remove(it) }
        routeOverlays.clear()

        // Draw alternate routes first (so they are underneath)
        allRoutes.forEachIndexed { index, route ->
            if (index != selectedIndex) {
                addRouteToMap(route, index, isSelected = false)
            }
        }
        
        // Draw selected route last (on top)
        if (selectedIndex in allRoutes.indices) {
            addRouteToMap(allRoutes[selectedIndex], selectedIndex, isSelected = true)
        }

        // Add start/end markers for the selected route
        if (allRoutes.isNotEmpty()) {
            val firstRoute = allRoutes[selectedIndex.coerceIn(allRoutes.indices)]
            addStartEndMarkers(firstRoute)
            
            // Zoom to route
            map.post {
                val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(firstRoute.points)
                map.zoomToBoundingBox(boundingBox, true, 150)
            }
        }
        
        map.invalidate()
    }

    private fun addRouteToMap(route: RouteResult, index: Int, isSelected: Boolean) {
        val polyline = Polyline().apply {
            setPoints(route.points)
            if (isSelected) {
                outlinePaint.color = Color.parseColor("#00E0FF")
                outlinePaint.strokeWidth = 18f
                outlinePaint.alpha = 255
            } else {
                outlinePaint.color = Color.parseColor("#83958C")
                outlinePaint.strokeWidth = 12f
                outlinePaint.alpha = 180
            }
            outlinePaint.strokeCap = Paint.Cap.ROUND
            setOnClickListener { _, _, _ ->
                selectRoute(index)
                true
            }
        }
        routeOverlays.add(polyline)
        map.overlays.add(polyline)
    }

    private fun selectRoute(index: Int) {
        setupRoutes(index)
        updateStatsForRoute(allRoutes[index], index == 0)
    }

    private fun updateStatsForRoute(route: RouteResult, isDirect: Boolean) {
        val allPotholes = PotholeRepository.getAllPotholes(this)
        val stats = calculateRouteStats(route, allPotholes)
        
        val distKm = route.distanceMeters / 1000.0
        binding.tvAnalysisDistance.text = if (isDirect) "Direct Path • ${String.format("%.1f km", distKm)}" else String.format("%.1f km", distKm)
        
        val totalMinutes = (distKm / 30.0 * 60.0).toInt().coerceAtLeast(1)
        binding.tvAnalysisTime.text = when {
            totalMinutes >= 60 -> {
                val hrs = totalMinutes / 60
                val mins = totalMinutes % 60
                if (mins == 0) "$hrs hr" else "$hrs hr $mins min"
            }
            else -> "$totalMinutes min"
        }
        
        binding.tvAnalysisPotholes.text = stats.first.toString()
        binding.tvAnalysisBumps.text = stats.second.toString()
        binding.tvAnalysisQuality.text = stats.third

        val color = when (stats.third) {
            "EXCELLENT" -> Color.parseColor("#00FFC2")
            "GREAT", "GOOD" -> Color.parseColor("#00E0FF")
            "FAIR" -> Color.parseColor("#FFD700")
            "HAZARDOUS" -> Color.parseColor("#FF3B30")
            else -> Color.GRAY
        }
        binding.tvAnalysisQuality.setTextColor(color)

        // Refresh hazards on map for the new selected route
        refreshHazardMarkers(route, allPotholes)
    }

    private fun calculateRouteStats(route: RouteResult, allPotholes: List<PotholeData>): Triple<Int, Int, String> {
        var potholes = 0
        var bumps = 0
        
        for (p in allPotholes) {
            for (point in route.points) {
                if (point.distanceToAsDouble(p.location) < 50.0) {
                    if (p.type == RoadFeature.POTHOLE) potholes++ else bumps++
                    break
                }
            }
        }
        
        val totalHazards = potholes + bumps
        val density = totalHazards / (route.distanceMeters / 1000.0)
        val quality = when {
            density < 0.5 -> "EXCELLENT"
            density < 1.5 -> "GREAT"
            density < 3.0 -> "GOOD"
            density < 5.0 -> "FAIR"
            else -> "HAZARDOUS"
        }
        
        return Triple(potholes, bumps, quality)
    }

    private fun refreshHazardMarkers(route: RouteResult, allPotholes: List<PotholeData>) {
        hazardMarkers.forEach { map.overlays.remove(it) }
        hazardMarkers.clear()

        for (p in allPotholes) {
            for (point in route.points) {
                if (point.distanceToAsDouble(p.location) < 50.0) {
                    val marker = Marker(map).apply {
                        position = p.location
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        val baseColor = if (p.type == RoadFeature.SPEED_BUMP) 
                            Color.parseColor("#00FFC2") else Color.parseColor("#FFD700")
                        androidx.core.content.ContextCompat.getDrawable(this@RouteAnalysisActivity, R.drawable.ic_location_pin)?.let { icon ->
                            icon.setTint(baseColor)
                            this.icon = icon
                        }
                        title = "${p.severity.name} ${p.type.name}"
                    }
                    hazardMarkers.add(marker)
                    map.overlays.add(marker)
                    break
                }
            }
        }
        map.invalidate()
    }

    private fun addStartEndMarkers(route: RouteResult) {
        // Clear existing start/end if any (though setupRoutes clears all)
        val startMarker = Marker(map).apply {
            position = route.points.first()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Start"
        }
        val endMarker = Marker(map).apply {
            position = route.points.last()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Destination"
        }
        map.overlays.add(startMarker)
        map.overlays.add(endMarker)
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
