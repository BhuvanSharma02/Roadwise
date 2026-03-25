package com.roadwise.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

enum class RoadFeature {
    POTHOLE, SPEED_BUMP, UNKNOWN
}

class BumpDetector(context: Context, private val onFeatureDetected: (RoadFeature, Float) -> Unit) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val THRESHOLD = 3.8f 
    private var lastZ = 0.0f
    
    // Signature Tracking
    private var pendingFeature: RoadFeature = RoadFeature.UNKNOWN
    private var pendingTimestamp: Long = 0
    private val SIGNATURE_WINDOW_MS = 600L // Time to complete the Up/Down cycle

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val z = event.values[2] 
            val currentTime = System.currentTimeMillis()
            
            if (lastZ != 0.0f) {
                val deltaZ = z - lastZ
                
                // State Machine Logic
                if (pendingFeature == RoadFeature.UNKNOWN) {
                    // Step 1: Detect the START of a movement
                    if (deltaZ > THRESHOLD) {
                        // Rising -> Potential Speed Bump
                        pendingFeature = RoadFeature.SPEED_BUMP
                        pendingTimestamp = currentTime
                    } else if (deltaZ < -THRESHOLD) {
                        // Falling -> Potential Pothole
                        pendingFeature = RoadFeature.POTHOLE
                        pendingTimestamp = currentTime
                    }
                } else {
                    // Step 2: Look for the COMPLETION of the signature
                    val timeElapsed = currentTime - pendingTimestamp
                    
                    if (timeElapsed > SIGNATURE_WINDOW_MS) {
                        // Timeout: It was just a single jolt, not a hump/hole
                        pendingFeature = RoadFeature.UNKNOWN
                    } else {
                        if (pendingFeature == RoadFeature.SPEED_BUMP && deltaZ < -THRESHOLD) {
                            // Up then Down! Signature for Speed Bump confirmed.
                            onFeatureDetected(RoadFeature.SPEED_BUMP, deltaZ)
                            pendingFeature = RoadFeature.UNKNOWN
                        } else if (pendingFeature == RoadFeature.POTHOLE && deltaZ > THRESHOLD) {
                            // Down then Up! Signature for Pothole confirmed.
                            onFeatureDetected(RoadFeature.POTHOLE, deltaZ)
                            pendingFeature = RoadFeature.UNKNOWN
                        }
                    }
                }
            }
            lastZ = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
