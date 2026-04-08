package com.roadwise.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.abs
import kotlin.math.sqrt

enum class RoadFeature {
    POTHOLE, SPEED_BUMP, UNKNOWN
}

class BumpDetector(
    context: Context,
    private val getCurrentSpeedKmh: () -> Int,
    private val onFeatureDetected: (RoadFeature, Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var threshold = context.getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
        .getFloat("pref_sensor_threshold", 3.8f)

    // Window size should be a power of 2 for FFT efficiency
    private val windowSize = 64
    private val zHistory = FloatArray(windowSize)
    private var historyIndex = 0
    private var samplesCount = 0

    private val MIN_SPEED_KMH = 15
    private var lastEventTime = 0L

    private val fft = FloatFFT_1D(windowSize.toLong())

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun updateThreshold(context: Context) {
        threshold = context.getSharedPreferences("roadwise_prefs", Context.MODE_PRIVATE)
            .getFloat("pref_sensor_threshold", 3.8f)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val currentSpeed = getCurrentSpeedKmh()

            if (currentSpeed < MIN_SPEED_KMH) {
                samplesCount = 0
                return
            }

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Horizontal Noise Filter
            if (abs(x) > threshold * 1.5f || abs(y) > threshold * 1.5f) return

            zHistory[historyIndex] = z
            historyIndex = (historyIndex + 1) % windowSize
            if (samplesCount < windowSize) samplesCount++

            if (samplesCount == windowSize) {
                analyzeWindow()
            }
        }
    }

    private fun analyzeWindow() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < 2000) return

        var maxAbsZ = 0f
        val fftData = FloatArray(windowSize * 2) // Real and Imaginary parts

        for (i in 0 until windowSize) {
            val v = zHistory[i]
            if (abs(v) > maxAbsZ) maxAbsZ = abs(v)
            fftData[i] = v // Fill real part
        }

        if (maxAbsZ > threshold) {
            // Perform FFT
            fft.realForward(fftData)

            // Extract Spectral Energy
            // Low Freq: 0 to 5Hz (roughly first 5 bins at ~100Hz sampling)
            // High Freq: 10Hz to 50Hz (bins 10 to 32)
            var lowFreqEnergy = 0f
            var highFreqEnergy = 0f

            for (i in 1 until windowSize / 2) {
                val real = fftData[2 * i]
                val imag = fftData[2 * i + 1]
                val magnitude = sqrt(real * real + imag * imag)

                if (i <= 5) lowFreqEnergy += magnitude
                else if (i > 8) highFreqEnergy += magnitude
            }

            // Spectral Ratio: Potholes have much more high-frequency noise
            val spectralRatio = highFreqEnergy / (lowFreqEnergy + 1e-6f)

            if (spectralRatio > 1.2f) {
                // High frequency energy dominant -> Pothole
                onFeatureDetected(RoadFeature.POTHOLE, maxAbsZ)
                lastEventTime = currentTime
                samplesCount = 0
            } else if (lowFreqEnergy > highFreqEnergy * 1.5f) {
                // Low frequency energy dominant -> Speed Breaker
                onFeatureDetected(RoadFeature.SPEED_BUMP, maxAbsZ)
                lastEventTime = currentTime
                samplesCount = 0
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
