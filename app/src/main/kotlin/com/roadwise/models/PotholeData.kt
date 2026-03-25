package com.roadwise.models

import org.osmdroid.util.GeoPoint
import com.roadwise.sensors.RoadFeature

data class PotholeData(
    val location: GeoPoint,
    val type: RoadFeature,
    val intensity: Float,
    val timestamp: Long = System.currentTimeMillis()
)
