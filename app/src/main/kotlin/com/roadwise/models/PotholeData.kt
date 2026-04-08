package com.roadwise.models

import org.osmdroid.util.GeoPoint
import com.roadwise.sensors.RoadFeature
import com.roadwise.utils.Severity

data class PotholeData(
    val location: GeoPoint,
    val type: RoadFeature,
    val intensity: Float,
    val severity: Severity = Severity.LOW,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePaths: List<String> = emptyList()
)
