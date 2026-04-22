package com.roadwise

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.roadwise.databinding.ActivityMainBinding
import com.roadwise.models.PotholeData
import com.roadwise.sensors.BumpDetector
import com.roadwise.sensors.RoadFeature
import com.roadwise.utils.DetectionManager
import com.roadwise.utils.PotholeRepository
import com.roadwise.utils.SessionManager
import com.roadwise.utils.Severity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.*
import androidx.lifecycle.lifecycleScope
import com.roadwise.mapping.AdaptiveRoadOverlay
import com.roadwise.routing.RoutingManager
import com.roadwise.routing.RouteResult
import com.roadwise.routing.PhotonFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import com.roadwise.utils.SafetyAlertManager
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.view.View
import android.animation.ValueAnimator
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bumpDetector: BumpDetector
    private lateinit var map: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var detectionManager: DetectionManager
    private lateinit var routingManager: RoutingManager
    private lateinit var safetyAlertManager: SafetyAlertManager
    private lateinit var sharedPrefs: SharedPreferences
    private var switchPulseAnimator: ValueAnimator? = null
    private var destinationMarker: Marker? = null
    private var verifiedPotholeCount = 0
    private var maxSpeedKmh = 0
    private var searchJob: Job? = null
    private var searchResults: List<PhotonFeature> = emptyList()
    private lateinit var adaptiveOverlay: AdaptiveRoadOverlay
    private val routeOverlays = mutableListOf<Polyline>()
    private var currentRoutes: List<com.roadwise.routing.RouteResult> = emptyList()

    // ActivityResultLauncher for RouteAnalysis result
    private val analysisLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val route = result.data?.getParcelableExtra<RouteResult>("SELECTED_ROUTE")
            route?.let { startNavigation(it) }
        } else {
            // Ensure route is cleared if we just come back without starting
            clearRouteFromMap()
        }
    }

    // ActivityResultLauncher for LoginActivity result
    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            updateNavForRole()
            val name = SessionManager.getUserName(this)
            Toast.makeText(this, "Welcome, $name!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val ctx = applicationContext
            Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
            Configuration.getInstance().userAgentValue = packageName

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // ONE-TIME RESET
            val prefs = getSharedPreferences("roadwise_internal", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("v2_data_reset", false)) {
                PotholeRepository.clearAll(this)
                prefs.edit().putBoolean("v2_data_reset", true).apply()
            }

            val allDetections = try {
                PotholeRepository.getAllPotholes(this)
            } catch (e: Exception) {
                Log.e("RoadWise", "Failed to load history", e)
                emptyList()
            }

            verifiedPotholeCount = allDetections.count { it.type == RoadFeature.POTHOLE }
            verifiedPotholeCount = allDetections.count { it.type == RoadFeature.POTHOLE }
            binding.potholeCount.text = verifiedPotholeCount.toString()

            detectionManager = DetectionManager { type, severity, intensity ->
                val loc = locationOverlay.myLocation
                    ?: locationOverlay.myLocationProvider?.lastKnownLocation?.let { GeoPoint(it) }

                if (loc != null && loc.latitude != 0.0) {
                    val data = PotholeData(loc, type, intensity, severity, System.currentTimeMillis(), emptyList())
                    PotholeRepository.savePothole(this, data)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && ::map.isInitialized) {
                            addHeatmapPoint(data)
                            adaptiveOverlay.refresh()
                            map.controller.animateTo(loc)
                            val severityLabel = severity.name
                            if (type == RoadFeature.POTHOLE) {
                                verifiedPotholeCount++
                                binding.potholeCount.text = verifiedPotholeCount.toString()
                                Toast.makeText(this, "⚠️ $severityLabel POTHOLE DETECTED!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "🏁 $severityLabel SPEED BUMP", Toast.LENGTH_SHORT).show()
                            }
                            map.invalidate()
                        }
                    }
                } else {
                    Log.e("RoadWise", "Detection ignored: No GPS fix")
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
            safetyAlertManager = SafetyAlertManager(this)
            sharedPrefs = getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
            setupMapGestures()

            allDetections.forEach { addHeatmapPoint(it) }

            PotholeRepository.fetchFromCloud(this) { combinedList ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && ::map.isInitialized) {
                        refreshMarkers()
                    }
                }
            }

            lifecycleScope.launch {
                while (isActive) {
                    val speedMs = locationOverlay.myLocationProvider?.lastKnownLocation?.speed ?: 0f
                    val speedKmh = (speedMs * 3.6).toInt()
                    if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
                    withContext(Dispatchers.Main) {
                        val monitoringOn = binding.toggleMonitoring.isChecked
                        if (!monitoringOn) {
                            binding.monitoringStatus.text = "PASSIVE (ALERTS ONLY)"
                            binding.monitoringStatus.setTextColor(Color.GRAY)
                        } else if (speedKmh < 5) {
                            binding.monitoringStatus.text = if (speedKmh == 0) "STATIONARY" else "IDLE (SLOW)"
                            binding.monitoringStatus.setTextColor(Color.GRAY)
                        } else {
                            binding.monitoringStatus.text = "MONITORING ACTIVE"
                            binding.monitoringStatus.setTextColor(Color.parseColor("#00FFC2"))
                        }
                        binding.speedValue.text = "$speedKmh km/h"
                    }

                    // Hazard Proximity Detection
                    if (sharedPrefs.getBoolean("pref_voice_alerts", true)) {
                        val location = locationOverlay.myLocation
                        val bearing = locationOverlay.myLocationProvider?.lastKnownLocation?.bearing ?: 0f
                        safetyAlertManager.checkHazards(location, bearing, speedKmh)
                    }

                    delay(1000)
                }
            }

            binding.speedValue.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Session Highlights")
                    .setMessage("Top Speed Today: $maxSpeedKmh km/h")
                    .setPositiveButton("Close", null)
                    .show()
                true
            }

            bumpDetector = BumpDetector(this, {
                val speedMs = locationOverlay.myLocationProvider?.lastKnownLocation?.speed ?: 0f
                (speedMs * 3.6).toInt()
            }) { type, intensity ->
                detectionManager.onSensorDetection(type, intensity)
            }
            
            // monitoring toggle logic
            val isMonitoringEnabled = sharedPrefs.getBoolean("pref_monitoring_enabled", true)
            binding.toggleMonitoring.isChecked = isMonitoringEnabled

            binding.toggleMonitoring.setOnCheckedChangeListener { _, isChecked ->
                sharedPrefs.edit().putBoolean("pref_monitoring_enabled", isChecked).apply()
                if (isChecked) {
                    bumpDetector.start()
                    startSwitchPulse()
                    Toast.makeText(this, "Monitoring Enabled", Toast.LENGTH_SHORT).show()
                } else {
                    bumpDetector.stop()
                    stopSwitchPulse()
                    Toast.makeText(this, "Monitoring Disabled (Passive Alerts Only)", Toast.LENGTH_SHORT).show()
                }
            }

            if (isMonitoringEnabled) {
                bumpDetector.start()
                startSwitchPulse()
            }

            if (!allPermissionsGranted()) {
                ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
            }

            // ── Navigation button listeners ────────────────────────────────────
            binding.navDrive.setOnClickListener {
                if (isInNavigationMode) exitNavigation()
                updateNavUI(it)
                locationOverlay.enableFollowLocation()
            }

            binding.navAlerts.setOnClickListener {
                updateNavUI(it)
                if (SessionManager.isAdmin(this)) {
                    startActivity(Intent(this, OverviewActivity::class.java))
                } else {
                    startActivity(Intent(this, HistoryActivity::class.java))
                }
            }

            binding.navSettings.setOnClickListener {
                updateNavUI(it)
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            binding.navAccount.setOnClickListener {
                if (SessionManager.isLoggedIn(this)) {
                    showLogoutDialog()
                } else {
                    loginLauncher.launch(Intent(this, LoginActivity::class.java))
                }
            }

            binding.btnStopNav.setOnClickListener {
                exitNavigation()
            }

            binding.btnRecenter.setOnClickListener {
                val loc = locationOverlay.myLocation
                    ?: locationOverlay.myLocationProvider?.lastKnownLocation?.let { GeoPoint(it) }

                if (loc != null) {
                    locationOverlay.enableFollowLocation()
                    map.controller.animateTo(loc)
                    map.controller.setZoom(19.0)
                } else {
                    Toast.makeText(this, "Searching for GPS...", Toast.LENGTH_SHORT).show()
                }
            }

            binding.btnTheme.setOnClickListener {
                val isNightMode = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
                AppCompatDelegate.setDefaultNightMode(
                    if (isNightMode) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
                )
            }

            // setupSearchBar()

            // Apply the correct nav state for the current session
            updateNavForRole()

            handleFocusIntent(intent)

        } catch (e: Exception) {
            Log.e("RoadWise", "FATAL STARTUP ERROR", e)
            Toast.makeText(this, "Startup error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Role-based Navigation ──────────────────────────────────────────────────

    private fun updateNavForRole() {
        val isAdmin    = SessionManager.isAdmin(this)
        val isLoggedIn = SessionManager.isLoggedIn(this)
        val teal       = ContextCompat.getColor(this, R.color.emerald_neon)
        val faded      = ContextCompat.getColor(this, R.color.text_med)

        if (isAdmin) {
            binding.navAlerts.setImageResource(R.drawable.ic_analytics)
            binding.navAlertsLabel.text = "OVERVIEW"
        } else {
            binding.navAlerts.setImageResource(R.drawable.ic_alerts)
            binding.navAlertsLabel.text = "HISTORY"
        }

        // Account icon: teal with ✓ tint when logged in
        binding.navAccount.setColorFilter(if (isLoggedIn) teal else faded)
        binding.navAccountLabel.setTextColor(if (isLoggedIn) teal else faded)
        binding.navAccountLabel.text = if (isLoggedIn) SessionManager.getUserName(this).uppercase().take(8) else "ACCOUNT"
    }

    private fun showLogoutDialog() {
        val email = SessionManager.getUserEmail(this)
        val role  = if (SessionManager.isAdmin(this)) "Admin" else "Standard User"
        AlertDialog.Builder(this)
            .setTitle("Signed In")
            .setMessage("$email\nRole: $role\n\nSign out of RoadWise?")
            .setPositiveButton("Sign Out") { _, _ ->
                SessionManager.logout(this)
                updateNavForRole()
                Toast.makeText(this, "Signed out successfully.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Nav UI helper ──────────────────────────────────────────────────────────

    private fun updateNavUI(active: View) {
        val faded = ContextCompat.getColor(this, R.color.text_med)
        val teal  = ContextCompat.getColor(this, R.color.emerald_neon)

        binding.navDrive.setColorFilter(faded)
        binding.navAlerts.setColorFilter(faded)
        binding.navSettings.setColorFilter(faded)
        binding.navDriveLabel.setTextColor(faded)
        binding.navAlertsLabel.setTextColor(faded)
        binding.navSettingsLabel.setTextColor(faded)

        when (active.id) {
            R.id.navDrive    -> { binding.navDrive.setColorFilter(teal);    binding.navDriveLabel.setTextColor(teal) }
            R.id.navAlerts   -> { binding.navAlerts.setColorFilter(teal);   binding.navAlertsLabel.setTextColor(teal) }
            R.id.navSettings -> { binding.navSettings.setColorFilter(teal); binding.navSettingsLabel.setTextColor(teal) }
        }
    }

    // ── Search Bar ─────────────────────────────────────────────────────────────

    private fun setupSearchBar() {
        /* Search logic hidden for Cyber-Sleek UI
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, mutableListOf()) {
            override fun getFilter(): android.widget.Filter {
                return object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?) = FilterResults()
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        if (count > 0) notifyDataSetChanged() else notifyDataSetInvalidated()
                    }
                }
            }
        }
        binding.searchPlace?.setAdapter(adapter)

        binding.searchPlace?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.length < 3) return
                searchJob?.cancel()
                searchJob = lifecycleScope.launch(Dispatchers.IO) {
                    delay(500)
                    withContext(Dispatchers.Main) { binding.searchProgress?.visibility = View.VISIBLE }
                    val loc     = locationOverlay.myLocation
                    val results = routingManager.searchPlaces(query, loc?.latitude, loc?.longitude)
                    withContext(Dispatchers.Main) {
                        binding.searchProgress?.visibility = View.GONE
                        searchResults = results
                        adapter.clear()
                        if (results.isEmpty()) adapter.add("No result found")
                        else adapter.addAll(results.map {
                            listOfNotNull(it.properties.name, it.properties.street, it.properties.city, it.properties.state, it.properties.country).joinToString(", ")
                        })
                        adapter.notifyDataSetChanged()
                    }
                }
            }
        })

        binding.searchPlace?.setOnItemClickListener { _, _, position, _ ->
            if (searchResults.isEmpty()) { binding.searchPlace?.setText(""); return@setOnItemClickListener }
            val feature = searchResults.getOrNull(position) ?: return@setOnItemClickListener
            val dest    = GeoPoint(feature.geometry.coordinates[1], feature.geometry.coordinates[0])
            val start   = locationOverlay.myLocation
            if (start == null) { Toast.makeText(this, "Waiting for GPS...", Toast.LENGTH_SHORT).show(); return@setOnItemClickListener }
            map.overlays.remove(destinationMarker)
            destinationMarker = Marker(map).apply {
                this.position = dest
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = feature.properties.name ?: "Destination"
            }
            map.overlays.add(destinationMarker)
            map.controller.animateTo(dest)
            map.controller.setZoom(16.0)
            calculateAndDrawRoute(start, dest)
            binding.searchPlace?.dismissDropDown()
            binding.searchPlace?.clearFocus()
        }
        */
    }

    // ── Map Gestures ───────────────────────────────────────────────────────────

    private fun setupMapGestures() {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                val start = locationOverlay.myLocation
                if (start == null) { Toast.makeText(this@MainActivity, "Waiting for GPS location...", Toast.LENGTH_SHORT).show(); return false }
                map.overlays.remove(destinationMarker)
                destinationMarker = Marker(map).apply { position = p; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Destination" }
                map.overlays.add(destinationMarker)
                map.invalidate()
                calculateAndDrawRoute(start, p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                val types = arrayOf("Pothole", "Speed Bump")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Select Hazard Type")
                    .setItems(types) { _, typeWhich ->
                        val type = if (typeWhich == 0) RoadFeature.POTHOLE else RoadFeature.SPEED_BUMP
                        
                        val severities = arrayOf("Minor", "Moderate", "Severe", "Critical (Priority Repair)")
                        val intensities = arrayOf(0.8f, 1.5f, 2.5f, 3.5f)
                        
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Select Severity")
                            .setItems(severities) { _, sevWhich ->
                                val intensity = intensities[sevWhich]
                                simulateHazard(p, type, intensity)
                            }
                            .show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return true
            }
        }
        map.overlays.add(0, MapEventsOverlay(receiver))
    }

    // ── Routing ────────────────────────────────────────────────────────────────

    private fun calculateAndDrawRoute(start: GeoPoint, end: GeoPoint) {
        Toast.makeText(this, "Calculating Safe Route...", Toast.LENGTH_SHORT).show()
        val allPotholes = PotholeRepository.getAllPotholes(this)
        val hazards     = allPotholes.filter { it.intensity > 0.8f }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val results = routingManager.getRoute(start, end, hazards)
                withContext(Dispatchers.Main) { drawRoutes(results, allPotholes) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Analysis failed.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun drawRoutes(routes: List<com.roadwise.routing.RouteResult>, allPotholes: List<PotholeData>) {
        currentRoutes = routes
        clearRouteFromMap()
        if (routes.isEmpty()) { Toast.makeText(this, "No route found.", Toast.LENGTH_SHORT).show(); return }
        
        val intent = Intent(this, RouteAnalysisActivity::class.java).apply {
            putParcelableArrayListExtra("ALL_ROUTES", ArrayList(routes))
        }
        analysisLauncher.launch(intent)
    }

    private fun selectRoute(selected: Polyline, data: com.roadwise.routing.RouteResult, allPotholes: List<PotholeData>) {
        val intent = Intent(this, RouteAnalysisActivity::class.java).apply {
            putParcelableArrayListExtra("ALL_ROUTES", ArrayList(currentRoutes))
            putExtra("SELECTED_INDEX", currentRoutes.indexOf(data))
        }
        analysisLauncher.launch(intent)
        clearRouteFromMap()
    }

    private fun clearRouteFromMap() {
        routeOverlays.forEach { map.overlays.remove(it) }
        routeOverlays.clear()
        destinationMarker?.let { map.overlays.remove(it); destinationMarker = null }
        map.invalidate()
    }

    private var isInNavigationMode = false

    private fun startNavigation(route: RouteResult) {
        isInNavigationMode = true
        binding.navPanel.visibility = View.VISIBLE
        binding.statsCard.visibility = View.GONE
        binding.searchCard.visibility = View.GONE
        binding.bottomNavCard.visibility = View.GONE
        
        // Draw the selected route
        val polyline = Polyline().apply {
            setPoints(route.points)
            outlinePaint.color = Color.parseColor("#00E0FF")
            outlinePaint.strokeWidth = 22f
            outlinePaint.strokeCap = Paint.Cap.ROUND
        }
        routeOverlays.add(polyline)
        map.overlays.add(polyline)
        
        // Zoom to start and follow
        map.controller.animateTo(route.points.first(), 18.0, 1000L)
        
        val marker = Marker(map).apply {
            position = route.points.last()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_location_pin)?.apply {
                setTint(Color.parseColor("#FF3B30"))
            }
            title = "Destination"
        }
        destinationMarker = marker
        map.overlays.add(marker)
        map.invalidate()
    }

    private fun exitNavigation() {
        isInNavigationMode = false
        binding.navPanel.visibility = View.GONE
        binding.statsCard.visibility = View.VISIBLE
        binding.searchCard.visibility = View.VISIBLE
        binding.bottomNavCard.visibility = View.VISIBLE
        clearRouteFromMap()
    }

    private fun calculateRouteStats(route: com.roadwise.routing.RouteResult, allPotholes: List<PotholeData>): Triple<Int, Int, String> {
        var potholes = 0
        var bumps = 0
        
        for (p in allPotholes) {
            // Check if pothole is within 50 meters of any point on the route
            for (point in route.points) {
                if (point.distanceToAsDouble(p.location) < 50.0) {
                    if (p.type == RoadFeature.POTHOLE) potholes++ else bumps++
                    break // count once per hazard
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

    // ── Adaptive Overlay ───────────────────────────────────────────────────────

    private fun initAdaptiveOverlay() {
        adaptiveOverlay = AdaptiveRoadOverlay(this, PotholeRepository)
        adaptiveOverlay.refresh()
        map.overlays.add(adaptiveOverlay)
        // binding.gradeLegend?.visibility = View.GONE
    }

    // ── Simulator / Heatmap ────────────────────────────────────────────────────

    private fun simulateHazard(location: GeoPoint, type: RoadFeature, userIntensity: Float) {
        val intensity = userIntensity
        val severity = when {
            intensity >= 2.5f -> Severity.HIGH
            intensity >= 1.5f -> Severity.MEDIUM
            else -> Severity.LOW
        }
        val data = PotholeData(location, type, intensity, severity, System.currentTimeMillis(), emptyList())
        PotholeRepository.savePothole(this, data)
        runOnUiThread {
            addHeatmapPoint(data); adaptiveOverlay.refresh(); map.invalidate()
            if (type == RoadFeature.POTHOLE) { verifiedPotholeCount++; binding.potholeCount.text = verifiedPotholeCount.toString() }
            Toast.makeText(this, "Simulated ${severity.name} ${type.name} added!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addHeatmapPoint(data: PotholeData) {
        if (!::map.isInitialized) return
        
        val baseColor = if (data.type == RoadFeature.SPEED_BUMP) Color.parseColor("#00FFC2") else Color.parseColor("#FFD700")

        val pinMarker = Marker(map)
        pinMarker.position = data.location
        pinMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        ContextCompat.getDrawable(this, R.drawable.ic_location_pin)?.let { icon -> 
            icon.setTint(baseColor)
            pinMarker.icon = icon 
        }
        pinMarker.title   = "${data.severity.name} ${data.type.name}"
        
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm:ss", Locale.getDefault())
        pinMarker.snippet = "Recorded: ${sdf.format(Date(data.timestamp))}\nIntensity: ${"%.1f".format(data.intensity)}g"
        
        map.overlays.add(pinMarker)
        map.invalidate()
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt()
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleFocusIntent(intent)
    }

    private fun handleFocusIntent(intent: Intent?) {
        val lat = intent?.getDoubleExtra("FOCUS_LAT", 0.0) ?: 0.0
        val lon = intent?.getDoubleExtra("FOCUS_LON", 0.0) ?: 0.0
        if (lat != 0.0 && lon != 0.0) {
            val focusPoint = GeoPoint(lat, lon)
            binding.map.postDelayed({
                map.controller.animateTo(focusPoint)
                map.controller.setZoom(20.0)
            }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::map.isInitialized) {
            map.onResume()
            refreshMarkers()
        }
        
        // Sync monitoring toggle with prefs (could have changed via notification)
        val isMonitoringEnabled = sharedPrefs.getBoolean("pref_monitoring_enabled", true)
        if (binding.toggleMonitoring.isChecked != isMonitoringEnabled) {
            binding.toggleMonitoring.isChecked = isMonitoringEnabled
            if (isMonitoringEnabled) {
                bumpDetector.start()
                startSwitchPulse()
            } else {
                bumpDetector.stop()
                stopSwitchPulse()
            }
        }
        
        updateNavForRole()  // Refresh nav state after returning from any activity
    }

    private fun refreshMarkers() {
        if (!::map.isInitialized) return
        
        // Remove all markers except location and destination
        map.overlays.removeAll { it is Marker && it != locationOverlay && it != destinationMarker }
        
        val allDetections = PotholeRepository.getAllPotholes(this)
        allDetections.forEach { addHeatmapPoint(it) }
        
        verifiedPotholeCount = allDetections.count { it.type == RoadFeature.POTHOLE }
        binding.potholeCount.text = verifiedPotholeCount.toString()
        
        if (::adaptiveOverlay.isInitialized) adaptiveOverlay.refresh()
        map.invalidate()
    }

    override fun onPause() {
        super.onPause()
        if (::map.isInitialized) map.onPause()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startSwitchPulse() {
        if (switchPulseAnimator != null) return
        switchPulseAnimator = ValueAnimator.ofFloat(1.0f, 0.4f).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                binding.toggleMonitoring.alpha = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun stopSwitchPulse() {
        switchPulseAnimator?.cancel()
        switchPulseAnimator = null
        binding.toggleMonitoring.alpha = 1.0f
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::map.isInitialized) map.onDetach()
        if (::bumpDetector.isInitialized) bumpDetector.close()
        if (::safetyAlertManager.isInitialized) safetyAlertManager.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
}
