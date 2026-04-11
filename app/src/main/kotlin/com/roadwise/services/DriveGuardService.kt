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
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.roadwise.MainActivity
import com.roadwise.R
import com.roadwise.models.PotholeData
import com.roadwise.sensors.BumpDetector
import com.roadwise.sensors.RoadFeature
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
    
    private var currentSpeedKmh = 0
    private var detectionCount = 0
    private var lastMovementTime = System.currentTimeMillis()
    private val IDLE_TIMEOUT_MS = 5 * 60 * 1000 // 5 minutes
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "drive_guard_channel"

    override fun onCreate() {
        super.onCreate()
        sharedPrefs = getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
        safetyAlertManager = SafetyAlertManager(this)
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        bumpDetector = BumpDetector(
            this,
            { currentSpeedKmh },
            { type, intensity ->
                // Registered from background
                val lastLoc = lastLocation
                if (lastLoc != null) {
                    val severity = com.roadwise.utils.Severity.MEDIUM // Background default
                    val data = PotholeData(GeoPoint(lastLoc), type, intensity, severity, System.currentTimeMillis(), emptyList())
                    PotholeRepository.savePothole(this, data)
                    
                    detectionCount++
                    updateNotification(currentSpeedKmh)
                }
            }
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))
        
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
                    
                    // Update movement timer
                    if (currentSpeedKmh > 5) {
                        lastMovementTime = System.currentTimeMillis()
                    }

                    // Check for idle timeout
                    if (System.currentTimeMillis() - lastMovementTime > IDLE_TIMEOUT_MS) {
                        Log.d("DriveGuardService", "Stationary for 5 mins. Stopping service.")
                        stopSelf()
                        return
                    }
                    
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
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoadWise Monitoring Active")
            .setContentText("Speed: $speed km/h • Hazards Detected: $detectionCount")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation) 
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(speed: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(speed))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        bumpDetector.stop()
        safetyAlertManager.shutdown()
    }
}
