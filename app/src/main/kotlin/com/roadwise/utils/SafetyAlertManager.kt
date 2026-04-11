package com.roadwise.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.roadwise.models.PotholeData
import com.roadwise.sensors.RoadFeature
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.math.abs

/**
 * SafetyAlertManager handles proximity-based voice warnings for road hazards.
 * Uses Android Text-To-Speech to alert users of approaching potholes or speedbumps.
 */
class SafetyAlertManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val announcedHazards = mutableSetOf<Long>()
    
    // Cooldown window to allow re-announcing a hazard if the user passes it again later (2 mins)
    private val announcementCooldown = 120_000L
    private val hazardLastAnnounced = mutableMapOf<Long, Long>()

    private var lastGlobalAlertTime = 0L
    private val GLOBAL_COOLDOWN = 10_000L // 10 seconds between ANY voice alerts
    private val CLUSTER_RADIUS = 40.0 // Meters to group hazards together

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("SafetyAlertManager", "TTS language not supported")
            } else {
                isInitialized = true
            }
        } else {
            Log.e("SafetyAlertManager", "TTS initialization failed")
        }
    }

    /**
     * Checks for nearby hazards in the direction of travel and announces them if appropriate.
     */
    fun checkHazards(userLocation: GeoPoint?, userBearing: Float, currentSpeedKmh: Int) {
        if (!isInitialized || userLocation == null || currentSpeedKmh < 10) return

        val currentTime = System.currentTimeMillis()
        
        // 1. Global Cooldown Check
        if (currentTime - lastGlobalAlertTime < GLOBAL_COOLDOWN) return

        val hazards = PotholeRepository.getAllPotholes(context)

        // Distances depend on speed (longer lead time for higher speeds)
        val minDistance = 15.0 // Too close to warn
        val maxDistance = if (currentSpeedKmh > 50) 100.0 else 60.0

        // Find potential primary hazards (the ones currently in trigger range)
        val triggers = hazards.filter { hazard ->
            val distance = userLocation.distanceToAsDouble(hazard.location)
            if (distance !in minDistance..maxDistance) return@filter false
            
            val bearingToHazard = userLocation.bearingTo(hazard.location).toFloat()
            val bearingDiff = abs(normalizeDegree(userBearing - bearingToHazard))
            if (bearingDiff > 45.0) return@filter false
            
            val lastTime = hazardLastAnnounced[hazard.timestamp] ?: 0L
            currentTime - lastTime > announcementCooldown
        }.sortedBy { userLocation.distanceToAsDouble(it.location) }

        if (triggers.isEmpty()) return

        // 2. Clustering & Prioritization
        val primary = triggers.first()
        
        // Find all other hazards near the primary one (even if not in 'trigger' range yet)
        val cluster = hazards.filter { other ->
            other.location.distanceToAsDouble(primary.location) < CLUSTER_RADIUS
        }

        if (cluster.size > 1) {
            // Find highest severity in the cluster
            val highestSeverity = cluster.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.LOW
            announceCluster(primary.type, highestSeverity, userLocation.distanceToAsDouble(primary.location).toInt())
            
            // Mark all in cluster as announced
            cluster.forEach { hazardLastAnnounced[it.timestamp] = currentTime }
        } else {
            announceHazard(primary, userLocation.distanceToAsDouble(primary.location).toInt())
            hazardLastAnnounced[primary.timestamp] = currentTime
        }

        lastGlobalAlertTime = currentTime
    }

    private fun announceHazard(hazard: PotholeData, distance: Int) {
        val type = if (hazard.type == RoadFeature.POTHOLE) "pothole" else "speed bump"
        val severity = getSeverityPrefix(hazard.severity)
        
        val roundedDistance = (distance / 10) * 10
        val message = "$severity $type ahead in $roundedDistance meters"

        speak(message, hazard.timestamp.toString())
    }

    private fun announceCluster(type: RoadFeature, maxSeverity: Severity, distance: Int) {
        val typeName = if (type == RoadFeature.POTHOLE) "pothole" else "speed bump"
        val severity = getSeverityPrefix(maxSeverity)
        
        val roundedDistance = (distance / 10) * 10
        val message = "$severity $typeName cluster ahead in $roundedDistance meters"

        speak(message, "CLUSTER_${System.currentTimeMillis()}")
    }

    private fun getSeverityPrefix(severity: Severity): String = when (severity) {
        Severity.HIGH -> "severe "
        Severity.MEDIUM -> ""
        Severity.LOW -> "minor "
    }

    private fun speak(message: String, utterId: String) {
        Log.d("SafetyAlertManager", "Speaking: $message")
        tts?.speak(message, TextToSpeech.QUEUE_ADD, null, utterId)
    }

    private fun normalizeDegree(degree: Float): Float {
        var d = degree % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isInitialized = false
    }
}
