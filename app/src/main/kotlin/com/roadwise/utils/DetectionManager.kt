package com.roadwise.utils

import android.util.Log
import android.graphics.Bitmap
import com.roadwise.BuildConfig
import com.roadwise.sensors.RoadFeature

class DetectionManager(private val onVerifiedFeature: (RoadFeature, Float, List<Bitmap>) -> Unit) {

    private var lastCameraDetectionTime: Long = 0
    private var lastConfidence: Float = 0f
    private var lastBitmaps = mutableListOf<Bitmap>()
    private val VERIFICATION_WINDOW_MS = 1500L 
    
    private var lockoutUntil: Long = 0
    private val LOCKOUT_DURATION_MS = 600L 

    fun onCameraDetection(confidence: Float, bitmaps: List<Bitmap>) {
        lastCameraDetectionTime = System.currentTimeMillis()
        lastConfidence = confidence
        if (bitmaps.isNotEmpty()) {
            lastBitmaps.clear()
            lastBitmaps.addAll(bitmaps)
        }
    }

    fun onSensorDetection(type: RoadFeature, intensity: Float) {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime < lockoutUntil) {
            if (BuildConfig.DEBUG) Log.d("RoadWise", "Ignoring rebound spike (Lockout active)")
            return
        }

        val hasVisualMatch = (currentTime - lastCameraDetectionTime < VERIFICATION_WINDOW_MS)
        
        val finalType = if (type == RoadFeature.POTHOLE && hasVisualMatch) {
            RoadFeature.POTHOLE
        } else if (type == RoadFeature.SPEED_BUMP) {
            RoadFeature.SPEED_BUMP
        } else {
            type 
        }

        lockoutUntil = currentTime + LOCKOUT_DURATION_MS

        if (BuildConfig.DEBUG) Log.d("RoadWise", "Feature: $finalType, Visual Match: $hasVisualMatch")
        onVerifiedFeature(finalType, intensity, if (hasVisualMatch) lastBitmaps.toList() else emptyList())
        
        lastCameraDetectionTime = 0
        lastBitmaps.clear()
    }
}
