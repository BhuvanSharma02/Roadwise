package com.roadwise.utils

import com.roadwise.models.PotholeData
import org.osmdroid.util.BoundingBox

data class RoadSegment(
    val segmentId: String,
    val boundingBox: BoundingBox,     // osmdroid BoundingBox
    val potholes: List<PotholeData>,
    val grade: RoadGrade,
    val score: Float                  // 0.0 (F) to 100.0 (A)
)

enum class RoadGrade(val label: String, val color: Int) {
    A("Excellent", 0xFF2ECC71.toInt()),   // green
    B("Good",      0xFF82E0AA.toInt()),   // light green
    C("Fair",      0xFFF4D03F.toInt()),   // amber
    D("Poor",      0xFFE67E22.toInt()),   // orange
    F("Critical",  0xFFE74C3C.toInt())    // red
}

object RoadQualityScorer {

    // Grid cell size in degrees (~100m x 100m at equator)
    private const val GRID_CELL_SIZE = 0.0009

    fun computeSegments(potholes: List<PotholeData>): List<RoadSegment> {
        // 1. Bucket every PotholeData into a lat/lon grid cell
        val grid = mutableMapOf<Pair<Int,Int>, MutableList<PotholeData>>()
        for (p in potholes) {
            val cellX = (p.location.latitude  / GRID_CELL_SIZE).toInt()
            val cellY = (p.location.longitude / GRID_CELL_SIZE).toInt()
            grid.getOrPut(Pair(cellX, cellY)) { mutableListOf() }.add(p)
        }

        // 2. Score each cell
        return grid.map { (cell, points) ->
            val score = computeScore(points)
            RoadSegment(
                segmentId = "${cell.first}_${cell.second}",
                boundingBox = cellToBoundingBox(cell),
                potholes   = points,
                grade      = scoreToGrade(score),
                score      = score
            )
        }
    }

    private fun computeScore(points: List<PotholeData>): Float {
        if (points.isEmpty()) return 100f
        // Weighted penalty: each point deducts points based on severity (using intensity)
        // PotholeData.intensity is G-force magnitude (0.0–3.0+)
        val penalty = points.sumOf { p ->
            when {
                p.intensity >= 2.5 -> 35.0   // Critical hit
                p.intensity >= 1.5 -> 20.0   // Severe
                p.intensity >= 0.8 -> 10.0   // Moderate
                else               ->  4.0   // Minor
            }
        }
        return (100f - penalty.toFloat()).coerceIn(0f, 100f)
    }

    private fun scoreToGrade(score: Float) = when {
        score >= 80 -> RoadGrade.A
        score >= 60 -> RoadGrade.B
        score >= 40 -> RoadGrade.C
        score >= 20 -> RoadGrade.D
        else        -> RoadGrade.F
    }

    private fun cellToBoundingBox(cell: Pair<Int,Int>): BoundingBox {
        val lat = cell.first  * GRID_CELL_SIZE
        val lon = cell.second * GRID_CELL_SIZE
        return BoundingBox(
            lat + GRID_CELL_SIZE, lon + GRID_CELL_SIZE,
            lat,                  lon
        )
    }
}
