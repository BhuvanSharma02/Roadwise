package com.roadwise

import android.Manifest
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
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var bumpDetector: BumpDetector
    private lateinit var map: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var detectionManager: DetectionManager
    private var verifiedPotholeCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ctx = applicationContext
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        detectionManager = DetectionManager { type, intensity ->
            val currentLocation = locationOverlay.myLocation
            if (currentLocation != null) {
                val data = PotholeData(currentLocation, type, intensity)
                runOnUiThread {
                    addHeatmapPoint(data)
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

        // Speedometer Loop
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                val speedMs = locationOverlay.myLocationProvider?.lastKnownLocation?.speed ?: 0f
                val speedKmh = (speedMs * 3.6).toInt()
                binding.speedValue.text = "$speedKmh km/h"
                handler.postDelayed(this, 1000)
            }
        })

        bumpDetector = BumpDetector(this) { type, intensity ->
            detectionManager.onSensorDetection(type, intensity)
        }
        bumpDetector.start()

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun addHeatmapPoint(data: PotholeData) {
        val marker = Marker(map)
        marker.position = data.location
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        
        val size = 120
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Fixed Colors: Red for Pothole, Cyan for Speed Bump
        val baseColor = if (data.type == RoadFeature.SPEED_BUMP) Color.CYAN else Color.RED

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
            val analyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, PotholeAnalyzer(this) { rects, width, height, conf ->
                    runOnUiThread { binding.graphicOverlay.updateRects(rects, width, height) }
                    if (rects.isNotEmpty()) detectionManager.onCameraDetection(conf)
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
