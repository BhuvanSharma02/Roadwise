package com.roadwise

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.roadwise.camera.PotholeAnalyzer
import com.roadwise.databinding.ActivityMainBinding
import com.roadwise.models.PotholeData
import com.roadwise.sensors.BumpDetector
import com.roadwise.sensors.RoadFeature
import com.roadwise.utils.DetectionManager
import com.roadwise.utils.PotholeRepository
import com.roadwise.utils.ImageAnalyzer
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.lifecycle.lifecycleScope
import com.roadwise.mapping.AdaptiveRoadOverlay
import com.roadwise.routing.RoutingManager
import com.roadwise.routing.PhotonFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.view.View
import android.animation.ValueAnimator
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var bumpDetector: BumpDetector
    private lateinit var map: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var detectionManager: DetectionManager
    private lateinit var routingManager: RoutingManager
    private var activeRoute: Polyline? = null
    private var destinationMarker: Marker? = null
    private var verifiedPotholeCount = 0
    private var maxSpeedKmh = 0
    private var isCameraActive = true
    private var searchJob: Job? = null
    private var searchResults: List<PhotonFeature> = emptyList()
    private lateinit var adaptiveOverlay: AdaptiveRoadOverlay
    private val routeOverlays = mutableListOf<Polyline>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ctx = applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Persistent State: Load existing detections and populate map
        val allDetections = PotholeRepository.getAllPotholes(this)
        verifiedPotholeCount = allDetections.count { it.type == RoadFeature.POTHOLE }
        binding.potholeCount.text = verifiedPotholeCount.toString()

        detectionManager = DetectionManager { type, intensity, bitmaps ->
            val currentLocation = locationOverlay.myLocation
            // Guard: only save if GPS has a valid fix (non-null and not at 0,0)
            if (currentLocation != null && currentLocation.latitude != 0.0 && currentLocation.longitude != 0.0) {
                val imagePaths = mutableListOf<String>()
                
                val bestBitmap = ImageAnalyzer.getClearestBitmap(bitmaps)
                if (bestBitmap != null) {
                    saveBitmapToDisk(bestBitmap)?.let { imagePaths.add(it) }
                }
                
                val data = PotholeData(currentLocation, type, intensity, System.currentTimeMillis(), imagePaths)
                PotholeRepository.savePothole(this, data)
                runOnUiThread {
                    addHeatmapPoint(data)
                    adaptiveOverlay.refresh()
                    map.invalidate()
                    if (type == RoadFeature.POTHOLE) {
                        verifiedPotholeCount++
                        binding.potholeCount.text = verifiedPotholeCount.toString()
                        Toast.makeText(this, "⚠️ POTHOLE VERIFIED!", Toast.LENGTH_SHORT).show()
                    } else if (type == RoadFeature.SPEED_BUMP) {
                        Toast.makeText(this, "🏁 SPEED BUMP", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        map = binding.map
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(19.0)
        map.controller.setCenter(GeoPoint(20.5937, 78.9629))

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        map.overlays.add(locationOverlay)

        initAdaptiveOverlay()

        routingManager = RoutingManager()
        setupMapGestures()

        // Load heatmap points for existing detections
        allDetections.forEach { addHeatmapPoint(it) }

        // Speed updater coroutine - lifecycle-safe, respects battery saver setting
        lifecycleScope.launch {
            while (isActive) {
                val batterySaver = getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
                    .getBoolean("pref_battery_saver", false)
                val speedMs = locationOverlay.myLocationProvider?.lastKnownLocation?.speed ?: 0f
                val speedKmh = (speedMs * 3.6).toInt()
                if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
                binding.speedValue.text = "$speedKmh km/h"
                delay(if (batterySaver) 2000L else 1000L)
            }
        }

        binding.speedValue.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Session Highlights")
                .setMessage("Top Speed Today: $maxSpeedKmh km/h")
                .setPositiveButton("Close", null)
                .show()
            true
        }

        bumpDetector = BumpDetector(this) { type, intensity ->
            detectionManager.onSensorDetection(type, intensity)
        }
        bumpDetector.start()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)

        setupMovableCamera()

        binding.navDrive.setOnClickListener {
            updateNavUI(it)
            binding.cameraCard.visibility = View.VISIBLE
            if (!isCameraActive) {
                startCamera()
                isCameraActive = true
            }
            locationOverlay.enableFollowLocation()
        }

        binding.navAlerts.setOnClickListener {
            updateNavUI(it)
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.navSettings.setOnClickListener {
            updateNavUI(it)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnRecenter.setOnClickListener {
            locationOverlay.myLocation?.let { location ->
                map.controller.animateTo(location)
                map.controller.setZoom(19.0)
            } ?: Toast.makeText(this, "Searching for GPS...", Toast.LENGTH_SHORT).show()
        }

        binding.btnHideCamera.setOnClickListener {
            binding.cameraCard.visibility = View.GONE
        }

        binding.btnTheme.setOnClickListener {
            val isNightMode = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
            AppCompatDelegate.setDefaultNightMode(if (isNightMode) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
        }

        setupSearchBar()
        
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onResume() {
        super.onResume()
        bumpDetector.updateThreshold(this)
    }

    private fun setupSearchBar() {
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, mutableListOf()) {
            override fun getFilter(): android.widget.Filter {
                return object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        return FilterResults() // Bypass UI string matching - Nominatim already filtered it!
                    }
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        if (count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
                    }
                }
            }
        }
        binding.searchPlace.setAdapter(adapter)

        binding.searchPlace.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.length < 3) return

                searchJob?.cancel()
                searchJob = lifecycleScope.launch(Dispatchers.IO) {
                    delay(500) // Output debounce
                    
                    withContext(Dispatchers.Main) {
                        binding.searchProgress.visibility = View.VISIBLE
                    }
                    
                    val loc = locationOverlay.myLocation
                    val results = routingManager.searchPlaces(query, loc?.latitude, loc?.longitude)
                    
                    withContext(Dispatchers.Main) {
                        binding.searchProgress.visibility = View.GONE
                        searchResults = results
                        adapter.clear()
                        if (results.isEmpty()) {
                            adapter.add("No result found")
                        } else {
                            adapter.addAll(results.map {
                                val p = it.properties
                                listOfNotNull(p.name, p.street, p.city, p.state, p.country).joinToString(", ")
                            })
                        }
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        })

        binding.searchPlace.setOnItemClickListener { _, _, position, _ ->
            if (searchResults.isEmpty()) {
                binding.searchPlace.setText("")
                return@setOnItemClickListener
            }
            
            val feature = searchResults.getOrNull(position) ?: return@setOnItemClickListener
            
            val lon = feature.geometry.coordinates[0]
            val lat = feature.geometry.coordinates[1]
            val dest = GeoPoint(lat, lon)
            
            val startCoords = locationOverlay.myLocation
            if (startCoords == null) {
                Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            
            map.overlays.remove(destinationMarker)
            destinationMarker = Marker(map).apply {
                this.position = dest
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = feature.properties.name ?: "Destination"
            }
            map.overlays.add(destinationMarker)
            
            map.controller.animateTo(dest)
            map.controller.setZoom(16.0)
            
            calculateAndDrawRoute(startCoords, dest)
            binding.searchPlace.dismissDropDown()
            binding.searchPlace.clearFocus()
        }
    }
    
    private fun setupMapGestures() {
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                val start = locationOverlay.myLocation
                if (start == null) {
                    Toast.makeText(this@MainActivity, "Waiting for GPS location...", Toast.LENGTH_SHORT).show()
                    return false
                }
                
                // Set Destination Marker
                map.overlays.remove(destinationMarker)
                destinationMarker = Marker(map).apply {
                    position = p
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Destination"
                }
                map.overlays.add(destinationMarker)
                map.invalidate()
                
                calculateAndDrawRoute(start, p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("RoadWise Simulator")
                    .setMessage("Add a simulated hazard at this location?")
                    .setPositiveButton("Pothole") { _, _ -> simulateHazard(p, RoadFeature.POTHOLE) }
                    .setNeutralButton("Speed Bump") { _, _ -> simulateHazard(p, RoadFeature.SPEED_BUMP) }
                    .setNegativeButton("Cancel", null)
                    .show()
                return true
            }
        }
        val mapEventsOverlay = MapEventsOverlay(mapEventsReceiver)
        map.overlays.add(0, mapEventsOverlay) // Add at bottom
    }

    private fun calculateAndDrawRoute(start: GeoPoint, end: GeoPoint) {
        Toast.makeText(this, "Calculating Safe Route...", Toast.LENGTH_SHORT).show()
        val allPotholes = PotholeRepository.getAllPotholes(this)
        // Filter: only avoid significant hazards (intensity > 0.8) for better performance
        val hazardsToAvoid = allPotholes.filter { it.intensity > 0.8f }
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Request up to 3 route candidates that avoid hazards
                val results = routingManager.getRoute(start, end, hazardsToAvoid)
                
                withContext(Dispatchers.Main) {
                    drawRoutes(results, allPotholes)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Analysis failed.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun drawRoutes(routes: List<com.roadwise.routing.RouteResult>, allPotholes: List<PotholeData>) {
        // Clear existing route overlays
        routeOverlays.forEach { map.overlays.remove(it) }
        routeOverlays.clear()

        if (routes.isEmpty()) {
            Toast.makeText(this, "No route found.", Toast.LENGTH_SHORT).show()
            return
        }

        // Draw multiple candidates - First one (Index 0) is the most recent "best" path
        // We add them in reverse order (alternatives first) so the Active route is on top
        routes.reversed().forEachIndexed { revIndex, routeResult ->
            val originalIndex = routes.size - 1 - revIndex
            val polyline = Polyline().apply {
                setPoints(routeResult.points)
                // Style: Active (Original Index 0) vs Alternative
                if (originalIndex == 0) {
                    outlinePaint.color = Color.parseColor("#3B82F6") // Bright Blue
                    outlinePaint.strokeWidth = 18f
                    updateQualityLabel(allPotholes, routeResult.distanceMeters)
                } else {
                    outlinePaint.color = Color.parseColor("#94A3B8") // Slate Gray
                    outlinePaint.alpha = 200
                    outlinePaint.strokeWidth = 14f
                }
                
                outlinePaint.style = Paint.Style.STROKE
                outlinePaint.strokeCap = Paint.Cap.ROUND

                setOnClickListener { poly, _, _ ->
                    selectRoute(poly as Polyline, routeResult, allPotholes)
                    true
                }
            }
            routeOverlays.add(polyline)
            map.overlays.add(polyline) // Add to top by default
        }
        
        map.invalidate()
        Toast.makeText(this, "Found ${routes.size} route candidates", Toast.LENGTH_SHORT).show()
    }

    private fun selectRoute(selected: Polyline, data: com.roadwise.routing.RouteResult, allPotholes: List<PotholeData>) {
        routeOverlays.forEach { poly ->
            if (poly == selected) {
                poly.outlinePaint.color = Color.parseColor("#3B82F6")
                poly.outlinePaint.alpha = 255
                poly.outlinePaint.strokeWidth = 18f
                updateQualityLabel(allPotholes, data.distanceMeters)
            } else {
                poly.outlinePaint.color = Color.parseColor("#94A3B8")
                poly.outlinePaint.alpha = 200
                poly.outlinePaint.strokeWidth = 14f
            }
        }
        // Force simple re-ordering (selected on top of others)
        map.overlays.remove(selected)
        map.overlays.add(selected)
        
        map.invalidate()
        Toast.makeText(this, "Route Selected", Toast.LENGTH_SHORT).show()
    }

    private fun updateQualityLabel(allPotholes: List<PotholeData>, distanceMeters: Double) {
        val routeDistKm = distanceMeters / 1000.0
        val density = allPotholes.size / routeDistKm
        val qualityLabel = when {
            density < 0.5 -> "EXCELLENT"
            density < 1.5 -> "GREAT"
            density < 3.0 -> "GOOD"
            density < 5.0 -> "FAIR"
            else -> "HAZARDOUS"
        }
        binding.qualityValue.text = qualityLabel
    }



    private fun saveBitmapToDisk(bitmap: Bitmap): String? {
        return try {
            val fileName = "pothole_${UUID.randomUUID()}.jpg"
            val file = File(getExternalFilesDir(null), fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.flush()
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun initAdaptiveOverlay() {
        adaptiveOverlay = AdaptiveRoadOverlay(this, PotholeRepository)
        adaptiveOverlay.refresh()
        map.overlays.add(adaptiveOverlay)

        map.addMapListener(object : MapListener {
            private var lastTier = -1 // -1 = uninitialized

            override fun onZoom(e: ZoomEvent): Boolean {
                val zoom = e.zoomLevel
                val tier = if (zoom < 15.0) 0 else 1
                
                // Toggle Legend Visibility
                binding.gradeLegend.visibility = if (tier == 0) View.VISIBLE else View.GONE

                if (tier != lastTier) {
                    lastTier = tier
                    // Crossfade the overlay by animating alpha
                    ValueAnimator.ofInt(0, 255).apply {
                        duration = 400
                        addUpdateListener {
                            adaptiveOverlay.setAlpha(it.animatedValue as Int)
                            map.invalidate()
                        }
                        start()
                    }
                }
                return true
            }

            override fun onScroll(e: ScrollEvent): Boolean = true
        })
    }

    private fun simulateHazard(location: GeoPoint, type: RoadFeature) {
        val intensity = if (type == RoadFeature.POTHOLE) 2.6f else 1.2f
        val data = PotholeData(location, type, intensity, System.currentTimeMillis(), emptyList())
        
        PotholeRepository.savePothole(this, data)
        
        runOnUiThread {
            addHeatmapPoint(data)
            adaptiveOverlay.refresh()
            map.invalidate()
            
            if (type == RoadFeature.POTHOLE) {
                verifiedPotholeCount++
                binding.potholeCount.text = verifiedPotholeCount.toString()
            }
            Toast.makeText(this, "Simulated ${type.name} added!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addHeatmapPoint(data: PotholeData) {
        val marker = Marker(map)
        marker.position = data.location
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        
        val size = 120
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        val baseColor = if (data.type == RoadFeature.SPEED_BUMP)
            Color.parseColor("#2DD4BF") // Brand teal for speed bumps
        else
            Color.parseColor("#FBBF24") // Brand amber for potholes

        val gradient = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(adjustAlpha(baseColor, 0.6f), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        
        val paint = Paint()
        paint.shader = gradient
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        
        marker.icon = BitmapDrawable(resources, bitmap)
        marker.setInfoWindow(null)
        
        map.overlays.add(marker)
        map.invalidate()
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                it.setAnalyzer(cameraExecutor, PotholeAnalyzer(this) { rects, width, height, conf, bitmaps ->
                    runOnUiThread { binding.graphicOverlay.updateRects(rects, width, height) }
                    if (rects.isNotEmpty()) detectionManager.onCameraDetection(conf, bitmaps)
                })
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            } catch (e: Exception) { Log.e("RoadWise", "Binding failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupMovableCamera() {
        var dX = 0f
        var dY = 0f

        binding.cameraCard.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    v.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                }
                else -> return@setOnTouchListener false
            }
            true
        }
    }

    private fun updateNavUI(active: View) {
        val faded = ContextCompat.getColor(this, R.color.text_med_emphasis_dark)
        val teal = ContextCompat.getColor(this, R.color.brand_teal)

        // Reset all icons and labels to faded
        binding.navDrive.setColorFilter(faded)
        binding.navAlerts.setColorFilter(faded)
        binding.navSettings.setColorFilter(faded)
        binding.navDriveLabel.setTextColor(faded)
        binding.navAlertsLabel.setTextColor(faded)
        binding.navSettingsLabel.setTextColor(faded)

        // Highlight active icon + label
        when (active.id) {
            R.id.navDrive -> {
                binding.navDrive.setColorFilter(teal)
                binding.navDriveLabel.setTextColor(teal)
            }
            R.id.navAlerts -> {
                binding.navAlerts.setColorFilter(teal)
                binding.navAlertsLabel.setTextColor(teal)
            }
            R.id.navSettings -> {
                binding.navSettings.setColorFilter(teal)
                binding.navSettingsLabel.setTextColor(teal)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()   
        bumpDetector.stop()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}
