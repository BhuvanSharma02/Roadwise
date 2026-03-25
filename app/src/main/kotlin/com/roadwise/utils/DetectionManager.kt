package com.roadwise.utils

import android.util.Log
import com.roadwise.sensors.RoadFeature

class DetectionManager(private val onVerifiedFeature: (RoadFeature, Float) -> Unit) {

    private var lastCameraDetectionTime: Long = 0
    private var lastConfidence: Float = 0f
    private val VERIFICATION_WINDOW_MS = 1500L 
    
    // Lock-out logic to prevent "Rebound" (climbing out of a hole) 
    // from being counted as a speed bump.
    private var lockoutUntil: Long = 0
    private val LOCKOUT_DURATION_MS = 600L 

    fun onCameraDetection(confidence: Float) {
        lastCameraDetectionTime = System.currentTimeMillis()
        lastConfidence = confidence
    }

    fun onSensorDetection(type: RoadFeature, intensity: Float) {
        val currentTime = System.currentTimeMillis()
        
        // 1. If we are in a lockout period, ignore this spike
        if (currentTime < lockoutUntil) {
            Log.d("RoadWise", "Ignoring rebound spike (Lockout active)")
            return
        }

        val hasVisualMatch = (currentTime - lastCameraDetectionTime < VERIFICATION_WINDOW_MS)
        
        // 2. Classification Logic
        val finalType = if (type == RoadFeature.POTHOLE && hasVisualMatch) {
            RoadFeature.POTHOLE
        } else if (type == RoadFeature.SPEED_BUMP) {
            // Only count as Speed Bump if it's the START of an event (not a rebound)
            RoadFeature.SPEED_BUMP
        } else {
            type // Sensor-only pothole
        }

        // 3. Start Lockout: Ignore all spikes for the next 600ms 
        // while the car stabilizes from this hit.
        lockoutUntil = currentTime + LOCKOUT_DURATION_MS

        Log.d("RoadWise", "Feature: $finalType, Visual Match: $hasVisualMatch")
        onVerifiedFeature(finalType, intensity)
        
        // Reset camera timer after a successful verification
        lastCameraDetectionTime = 0
    }
}
