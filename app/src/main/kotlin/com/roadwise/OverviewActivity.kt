package com.roadwise

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.roadwise.databinding.ActivityOverviewBinding
import com.roadwise.mapping.HeatmapOverlay
import com.roadwise.mapping.HotspotAdapter
import com.roadwise.models.AnalysisHotspot
import com.roadwise.models.PotholeData
import com.roadwise.sensors.RoadFeature
import com.roadwise.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class OverviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOverviewBinding
    private lateinit var map: MapView
    private var allData: List<PotholeData> = emptyList()
    private var currentFilter: Severity? = null
    
    private var hotspotAdapter: HotspotAdapter? = null
    private var currentHotspots: List<AnalysisHotspot> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupMap()
        loadData()
    }

    private fun setupUI() {
        binding.btnOverviewBack.setOnClickListener { finish() }
        
        binding.btnShowList.setOnClickListener { showHotspotSheet() }
        
        binding.btnExportReport.setOnClickListener { exportDataToCsv() }

        binding.chipGroupFilters.setOnCheckedChangeListener { _, checkedId ->
            currentFilter = when (checkedId) {
                R.id.chipCritical -> Severity.HIGH
                R.id.chipModerate -> Severity.MEDIUM
                else -> null
            }
            renderData(allData)
        }
    }

    private fun setupMap() {
        map = binding.overviewMap
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(20.5937, 78.9629))

        val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map)
        locationOverlay.enableMyLocation()
        map.overlays.add(locationOverlay)
    }

    private fun loadData() {
        binding.overviewProgress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val local = PotholeRepository.getAllPotholes(this@OverviewActivity)
            withContext(Dispatchers.Main) {
                allData = local
                renderData(allData)
            }

            PotholeRepository.fetchFromCloud(this@OverviewActivity) { combined ->
                runOnUiThread {
                    allData = combined
                    renderData(allData)
                }
            }
        }
    }

    private fun renderData(potholes: List<PotholeData>) {
        binding.overviewProgress.visibility = View.GONE

        // Apply filtering
        val filtered = if (currentFilter == null) potholes 
                      else potholes.filter { it.severity == currentFilter || (currentFilter == Severity.HIGH && it.intensity >= 8f) }

        if (filtered.isEmpty()) {
            map.overlays.removeAll { it is HeatmapOverlay || it is Marker }
            updateStats(filtered)
            map.invalidate()
            return
        }

        map.overlays.removeAll { it is HeatmapOverlay || it is Marker }

        // 1. Heatmap
        val heatmap = HeatmapOverlay(this, filtered)
        map.overlays.add(0, heatmap)

        // 2. Markers for critical clusters
        val criticalClusters = heatmap.clusters.filter { it.dominantGrade == RoadGrade.D || it.dominantGrade == RoadGrade.F }
        for (cluster in criticalClusters.take(20)) {
            val marker = Marker(map).apply {
                position = cluster.center
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Grade ${cluster.dominantGrade.name}"
                snippet = "${cluster.count} hazards detected here."
                setOnMarkerClickListener { m, _ ->
                    m.showInfoWindow()
                    reverseGeocodeAndUpdateTitle(m, cluster.center)
                    true
                }
            }
            map.overlays.add(marker)
        }

        // 3. Prepare hotspots for the list
        val segments = RoadQualityScorer.computeSegments(filtered)
        currentHotspots = segments.filter { it.grade == RoadGrade.D || it.grade == RoadGrade.F || it.grade == RoadGrade.C }
            .sortedBy { it.score } // Lower score = more dangerous
            .map { segment ->
                AnalysisHotspot(
                    center = GeoPoint(segment.boundingBox.centerLatitude, segment.boundingBox.centerLongitude),
                    grade = segment.grade,
                    count = segment.potholes.size,
                    score = segment.score.toDouble(),
                    // Pass the underlying potholes to enable remediation feature
                    potholes = segment.potholes
                )
            }
            .take(15)

        hotspotAdapter?.updateData(currentHotspots)
        updateStats(filtered)
        map.invalidate()
    }

    private fun updateStats(potholes: List<PotholeData>) {
        val criticalCount = potholes.count { it.severity == Severity.HIGH || it.intensity >= 8f }
        binding.tvTotalPotholes.text = potholes.size.toString()
        binding.tvCriticalCount.text = criticalCount.toString()
    }

    private fun showHotspotSheet() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.layout_hotspot_sheet, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvHotspots)
        val emptyState = view.findViewById<android.widget.TextView>(R.id.tvEmptyState)
        
        if (currentHotspots.isEmpty()) {
            rv.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            rv.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
        }
        
        hotspotAdapter = HotspotAdapter(currentHotspots, 
        onItemClick = { hotspot ->
            map.controller.animateTo(hotspot.center)
            map.controller.setZoom(17.0)
            dialog.dismiss()
        },
        onResolveClick = { hotspot ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Mark as Resolved?")
                .setMessage("This will remove all ${hotspot.count} hazards in this area from the active map. Are you sure the crew has fixed this?")
                .setPositiveButton("Resolved") { _, _ ->
                    binding.overviewProgress.visibility = View.VISIBLE
                    dialog.dismiss()
                    PotholeRepository.resolveHotspot(this, hotspot.potholes) {
                        Toast.makeText(this, "Hotspot Resolved! Map updated.", Toast.LENGTH_SHORT).show()
                        loadData() // Refetch constraints + Re-render
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        })
        
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = hotspotAdapter
        
        dialog.setContentView(view)
        dialog.show()
        
        // Asynchronously fetch addresses for the hotspots currently visible in the list
        enrichHotspotAddresses()
    }

    private fun enrichHotspotAddresses() {
        lifecycleScope.launch(Dispatchers.IO) {
            val geocoder = android.location.Geocoder(this@OverviewActivity, Locale.getDefault())
            
            for (i in currentHotspots.indices) {
                val hotspot = currentHotspots[i]
                if (hotspot.address.isNotBlank() && !hotspot.address.startsWith("Zone") && !hotspot.address.startsWith("Error")) continue
                
                try {
                    var finalAddress = "Unknown Location"
                    
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        // For newer Android versions
                        geocoder.getFromLocation(hotspot.center.latitude, hotspot.center.longitude, 1) { addresses ->
                            if (addresses.isNotEmpty()) {
                                val addr = addresses[0]
                                val road = addr.thoroughfare ?: ""
                                val subLocality = addr.subLocality ?: ""
                                val city = addr.locality ?: addr.subAdminArea ?: ""
                                
                                val parts = listOf(road, subLocality, city).filter { it.isNotBlank() }
                                if (parts.isNotEmpty()) {
                                    finalAddress = parts.joinToString(", ")
                                } else {
                                    finalAddress = addr.getAddressLine(0)?.split(",")?.take(2)?.joinToString(", ") ?: "Unknown Location"
                                }
                            }
                            
                            lifecycleScope.launch(Dispatchers.Main) {
                                updateHotspotAddress(i, hotspot, finalAddress)
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(hotspot.center.latitude, hotspot.center.longitude, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val addr = addresses[0]
                            val road = addr.thoroughfare ?: ""
                            val subLocality = addr.subLocality ?: ""
                            val city = addr.locality ?: addr.subAdminArea ?: ""
                            
                            val parts = listOf(road, subLocality, city).filter { it.isNotBlank() }
                            if (parts.isNotEmpty()) {
                                finalAddress = parts.joinToString(", ")
                            } else {
                                finalAddress = addr.getAddressLine(0)?.split(",")?.take(2)?.joinToString(", ") ?: "Unknown Location"
                            }
                        }
                        withContext(Dispatchers.Main) {
                            updateHotspotAddress(i, hotspot, finalAddress)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RoadWise-Geocode", "Native Geocoder failed", e)
                    withContext(Dispatchers.Main) {
                        updateHotspotAddress(i, hotspot, "Error: ${e.javaClass.simpleName}")
                    }
                }
                delay(500)
            }
        }
    }
    
    private fun updateHotspotAddress(index: Int, hotspot: AnalysisHotspot, newAddress: String) {
        if (index < currentHotspots.size) {
            val updated = currentHotspots.toMutableList()
            updated[index] = hotspot.copy(address = newAddress.ifBlank { "Unknown Location" })
            currentHotspots = updated
            hotspotAdapter?.updateData(currentHotspots)
        }
    }

    private fun exportDataToCsv() {
        if (allData.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = "RoadWise_Analysis_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.csv"
                val file = File(getExternalFilesDir(null), fileName)
                val out = FileOutputStream(file)
                
                out.write("Latitude,Longitude,Type,Intensity,Severity,Timestamp\n".toByteArray())
                for (p in allData) {
                    val line = "${p.location.latitude},${p.location.longitude},${p.type.name},${p.intensity},${p.severity.name},${p.timestamp}\n"
                    out.write(line.toByteArray())
                }
                out.close()

                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(this@OverviewActivity, "${packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "RoadWise Infrastructure Report")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share Analysis Report"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OverviewActivity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun reverseGeocodeAndUpdateTitle(marker: Marker, point: GeoPoint) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://nominatim.openstreetmap.org/reverse?lat=${point.latitude}&lon=${point.longitude}&format=json"
                val connection = URL(url).openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", "RoadWiseApp/1.0 (admin@roadwise.org)")
                val json = JSONObject(connection.inputStream.bufferedReader().readText())
                val name = json.optString("display_name", "").split(",").take(2).joinToString(", ")
                withContext(Dispatchers.Main) {
                    marker.title = "$name\n${marker.title}"
                    marker.showInfoWindow()
                }
            } catch (_: Exception) { }
        }
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
