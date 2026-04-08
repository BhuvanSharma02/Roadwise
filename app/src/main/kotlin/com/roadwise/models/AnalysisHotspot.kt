package com.roadwise.models

import org.osmdroid.util.GeoPoint
import com.roadwise.utils.RoadGrade

data class AnalysisHotspot(
    val center: GeoPoint,
    val grade: RoadGrade,
    val count: Int,
    val score: Double,
    val address: String = "",
    val potholes: List<com.roadwise.models.PotholeData> = emptyList()
)
