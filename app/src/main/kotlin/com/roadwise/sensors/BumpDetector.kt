package com.roadwise.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.*

enum class RoadFeature {
    POTHOLE, SPEED_BUMP, SMOOTH
}

class BumpDetector(
    context: Context,
    private val getCurrentSpeedKmh: () -> Int,
    private val onFeatureDetected: (RoadFeature, Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    
    private val model = RoadModelInference(context)

    // ML Model Parameters: 40 samples @ 20Hz = 2 seconds
    private val windowSize = 40
    private val stepSize = 20 // 50% overlap
    
    private val xHistory = FloatArray(windowSize)
    private val yHistory = FloatArray(windowSize)
    private val zHistory = FloatArray(windowSize)
    
    private var writeIndex = 0
    private var samplesCount = 0

    private val MIN_SPEED_KMH = 8 // Slightly increased for reliability
    private var lastEventTime = 0L

    fun start() {
        accelerometer?.let {
            // SENSOR_DELAY_GAME is roughly 20ms (50Hz), we need 20Hz (50ms)
            // We can specify the delay in microseconds: 50,000us = 20Hz
            sensorManager.registerListener(this, it, 50000)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        model.close()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val currentSpeed = getCurrentSpeedKmh()

            // Only monitor if the vehicle is moving at a reasonable speed
            if (currentSpeed < MIN_SPEED_KMH) {
                samplesCount = 0
                return
            }

            // Convert to G-force (m/s^2 -> G)
            xHistory[writeIndex] = event.values[0] / 9.81f
            yHistory[writeIndex] = event.values[1] / 9.81f
            zHistory[writeIndex] = event.values[2] / 9.81f

            writeIndex = (writeIndex + 1) % windowSize
            samplesCount++

            // Process every 'stepSize' samples once the first window is full
            if (samplesCount >= windowSize && (samplesCount - windowSize) % stepSize == 0) {
                analyzeWindow()
            }
        }
    }

    private fun analyzeWindow() {
        val currentTime = System.currentTimeMillis()
        // Cooldown to prevent multiple detections of the same physical event
        if (currentTime - lastEventTime < 1500) return

        // 1. Extract 12 Features
        val features = extract12Features()
        
        // 2. Run ML Inference
        val prediction = model.predict(features)
        
        // 3. Extract Impact Magnitude (Peak-to-Peak Gs for vertical axis)
        val impactMagnitude = features[4] // z_peak_to_peak is at index 4

        // 4. Handle Results (Filtering Smooth Road as requested)
        when (prediction) {
            1 -> { // SPEED_BUMP
                onFeatureDetected(RoadFeature.SPEED_BUMP, impactMagnitude)
                lastEventTime = currentTime
            }
            2 -> { // POTHOLE
                onFeatureDetected(RoadFeature.POTHOLE, impactMagnitude)
                lastEventTime = currentTime
            }
            // 0 is SMOOTH - Ignoring as per user request
        }
    }

    private fun extract12Features(): FloatArray {
        // High-pass filtering matching the Python 'z - mean(z)' strategy
        val zFiltered = FloatArray(windowSize)
        val zMeanOrig = zHistory.average().toFloat()
        for (i in 0 until windowSize) {
            zFiltered[i] = zHistory[i] - zMeanOrig
        }

        val zMean = zFiltered.average().toFloat()
        val zMax = zFiltered.maxOrNull() ?: 0f
        val zMin = zFiltered.minOrNull() ?: 0f
        val zP2P = zMax - zMin
        
        // Variance and StdDev
        var zVar = 0f
        var xVar = 0f
        var yVar = 0f
        val xMean = xHistory.average().toFloat()
        val yMean = yHistory.average().toFloat()
        
        for (i in 0 until windowSize) {
            zVar += (zFiltered[i] - zMean).pow(2)
            xVar += (xHistory[i] - xMean).pow(2)
            yVar += (yHistory[i] - yMean).pow(2)
        }
        val zStd = sqrt(zVar / (windowSize - 1))
        val xStd = sqrt(xVar / (windowSize - 1))
        val yStd = sqrt(yVar / (windowSize - 1))

        // RMS & Energy & Impact Ratio
        var zRmsSum = 0f
        var zEnergySum = 0f
        var impactCount = 0
        for (v in zFiltered) {
            zRmsSum += v.pow(2)
            zEnergySum += v.pow(2)
            if (abs(v) > 1.2f) impactCount++
        }
        val zRms = sqrt(zRmsSum / windowSize)
        val impactRatio = impactCount.toFloat() / windowSize

        // Skewness & Kurtosis
        var skewSum = 0f
        var kurtSum = 0f
        if (zStd > 1e-6) {
            for (v in zFiltered) {
                val diff = (v - zMean) / zStd
                skewSum += diff.pow(3)
                kurtSum += diff.pow(4)
            }
        }
        val zSkew = (skewSum / windowSize)
        val zKurt = (kurtSum / windowSize)

        return floatArrayOf(
            zMean, zStd, zMax, zMin, zP2P, zRms, 
            xStd, yStd, zEnergySum, zSkew, zKurt, impactRatio
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
