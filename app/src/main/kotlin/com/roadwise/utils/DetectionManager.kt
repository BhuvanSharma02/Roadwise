package com.roadwise.utils

import android.util.Log
import android.graphics.Bitmap
import com.roadwise.BuildConfig
import com.roadwise.sensors.RoadFeature

import org.osmdroid.util.GeoPoint

enum class Severity {
    LOW, MEDIUM, HIGH
}

data class PendingDetection(
    val type: RoadFeature,
    val severity: Severity,
    val intensity: Float,
    val location: GeoPoint,
    val timestamp: Long
)

class DetectionManager(private val onVerifiedFeature: (RoadFeature, Severity, Float, GeoPoint) -> Unit) {

    private var lockoutUntil: Long = 0
    private val LOCKOUT_DURATION_MS = 1000L
    private val pendingDetections = mutableListOf<PendingDetection>()

    // Speed thresholds
    private val STABLE_SPEED_KMH = 8 // Detections at or above this are fired immediately
    private val RECOVERY_SPEED_KMH = 12 // Speed needed to confirm low-speed detections
    private val MAX_PENDING_AGE_MS = 15000L // Discard if speed doesn't recover within 15s

    fun onSensorDetection(type: RoadFeature, intensity: Float, currentSpeed: Int, location: GeoPoint?) {
        val currentTime = System.currentTimeMillis()

        if (currentTime < lockoutUntil || location == null) {
            return
        }

        val severity = when {
            intensity < 0.5f -> Severity.LOW
            intensity < 0.8f -> Severity.MEDIUM
            else -> Severity.HIGH
        }

        lockoutUntil = currentTime + LOCKOUT_DURATION_MS

        if (currentSpeed >= STABLE_SPEED_KMH) {
            // High speed detection: fire immediately and clear any stale pending
            if (BuildConfig.DEBUG) Log.d("RoadWise", "Immediate Detection: $type at $currentSpeed km/h")
            onVerifiedFeature(type, severity, intensity, location)
            // Optional: If we just hit a pothole at high speed, maybe any previous low-speed ones are related?
            // For now, let's just clear stale ones.
            cleanupStalePending(currentTime)
        } else {
            // Low speed detection: buffer it
            if (BuildConfig.DEBUG) Log.d("RoadWise", "Buffered Detection: $type at $currentSpeed km/h (Waiting for recovery)")
            pendingDetections.add(PendingDetection(type, severity, intensity, location, currentTime))
        }
    }

    fun onSpeedUpdate(currentSpeed: Int) {
        val currentTime = System.currentTimeMillis()
        cleanupStalePending(currentTime)

        if (currentSpeed >= RECOVERY_SPEED_KMH && pendingDetections.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d("RoadWise", "Speed Recovered to $currentSpeed km/h. Committing ${pendingDetections.size} detections.")
            pendingDetections.forEach { 
                onVerifiedFeature(it.type, it.severity, it.intensity, it.location)
            }
            pendingDetections.clear()
        }
    }

    private fun cleanupStalePending(currentTime: Long) {
        pendingDetections.removeAll { currentTime - it.timestamp > MAX_PENDING_AGE_MS }
    }
}
