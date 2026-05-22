package com.roadwise.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.roadwise.MainActivity
import com.roadwise.R
import com.roadwise.models.PotholeData
import com.roadwise.sensors.BumpDetector
import com.roadwise.sensors.RoadFeature
import com.roadwise.utils.DetectionManager
import com.roadwise.utils.Severity
import com.roadwise.utils.PotholeRepository
import com.roadwise.utils.SafetyAlertManager
import org.osmdroid.util.GeoPoint

class DriveGuardService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var safetyAlertManager: SafetyAlertManager
    private lateinit var bumpDetector: BumpDetector
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var detectionManager: DetectionManager
    
    private var currentSpeedKmh = 0
    private var detectionCount = 0
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "drive_guard_channel"

    override fun onCreate() {
        super.onCreate()
        sharedPrefs = getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
        safetyAlertManager = SafetyAlertManager(this)
        
        detectionManager = DetectionManager { type, severity, intensity, loc ->
            val data = PotholeData(loc, type, intensity, severity, System.currentTimeMillis(), emptyList())
            PotholeRepository.savePothole(this, data)
            detectionCount++
            updateNotification(currentSpeedKmh)
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        bumpDetector = BumpDetector(
            this,
            { currentSpeedKmh },
            { type, intensity ->
                val isMonitoringEnabled = sharedPrefs.getBoolean("pref_monitoring_enabled", true)
                if (!isMonitoringEnabled) return@BumpDetector

                val lastLoc = lastLocation
                detectionManager.onSensorDetection(type, intensity, currentSpeedKmh, lastLoc?.let { GeoPoint(it) })
            }
        )

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(0))
        }
        
        startLocationUpdates()
        bumpDetector.start()
    }

    private var lastLocation: Location? = null

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    lastLocation = location
                    currentSpeedKmh = (location.speed * 3.6).toInt()
                    
                    // Trigger buffered detection recovery
                    detectionManager.onSpeedUpdate(currentSpeedKmh)
                    
                    // Update Notification
                    updateNotification(currentSpeedKmh)
                    
                    // Check Alerts
                    safetyAlertManager.checkHazards(GeoPoint(location), location.bearing, currentSpeedKmh)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e("DriveGuardService", "Location permission missing", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "DriveGuard Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(speed: Int): Notification {
        val stopIntent = Intent(this, DriveGuardService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, DriveGuardService::class.java).apply {
            action = ACTION_TOGGLE_MONITORING
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val isMonitoring = sharedPrefs.getBoolean("pref_monitoring_enabled", true)
        val statusText = if (isMonitoring) "RECORDING ACTIVE" else "PASSIVE (ALERTS ONLY)"
        val toggleLabel = if (isMonitoring) "GO PASSIVE" else "START RECORDING"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoadWise: $statusText")
            .setContentText("Speed: $speed km/h • Hazards Saved: $detectionCount")
            .setSmallIcon(if (isMonitoring) R.drawable.ic_drive else android.R.drawable.ic_menu_mylocation) 
            .setContentIntent(mainPendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.emerald_neon))
            .addAction(android.R.drawable.ic_menu_compass, toggleLabel, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(speed: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(speed))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i("DriveGuardService", "Stop action received.")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MONITORING -> {
                val current = sharedPrefs.getBoolean("pref_monitoring_enabled", true)
                sharedPrefs.edit().putBoolean("pref_monitoring_enabled", !current).apply()
                updateNotification(currentSpeedKmh)
                Log.i("DriveGuardService", "Monitoring toggled to ${!current}")
            }
            ACTION_START -> {
                Log.i("DriveGuardService", "Start action received.")
            }
        }
        return START_STICKY
    }

    companion object {
        const val ACTION_START = "START_SERVICE"
        const val ACTION_STOP = "STOP_SERVICE"
        const val ACTION_TOGGLE_MONITORING = "TOGGLE_MONITORING"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        bumpDetector.stop()
        safetyAlertManager.shutdown()
    }
}
