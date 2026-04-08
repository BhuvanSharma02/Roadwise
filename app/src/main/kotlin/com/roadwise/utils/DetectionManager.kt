package com.roadwise.utils

import android.util.Log
import android.graphics.Bitmap
import com.roadwise.BuildConfig
import com.roadwise.sensors.RoadFeature

enum class Severity {
    LOW, MEDIUM, HIGH
}

class DetectionManager(private val onVerifiedFeature: (RoadFeature, Severity, Float) -> Unit) {

    private var lockoutUntil: Long = 0
    private val LOCKOUT_DURATION_MS = 1000L // Increased lockout to prevent double detections

    fun onCameraDetection(confidence: Float, bitmaps: List<Bitmap>) {
        // Camera logic is now secondary, we can ignore it for sensor truth
    }

    fun onSensorDetection(type: RoadFeature, intensity: Float) {
        val currentTime = System.currentTimeMillis()

        if (currentTime < lockoutUntil) {
            return
        }

        // 1. Calculate Severity based on README.md standards (G-force intensity)
        val severity = when {
            intensity < 5.0f -> Severity.LOW
            intensity < 8.0f -> Severity.MEDIUM
            else -> Severity.HIGH
        }

        lockoutUntil = currentTime + LOCKOUT_DURATION_MS

        if (BuildConfig.DEBUG) Log.d("RoadWise", "Feature Detected: $type, Severity: $severity, Intensity: $intensity")

        // 2. Trust the Sensor 100% as requested by the teacher
        onVerifiedFeature(type, severity, intensity)
    }
}
