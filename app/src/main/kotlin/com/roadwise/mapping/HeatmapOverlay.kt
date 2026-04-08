package com.roadwise.mapping

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.roadwise.utils.RoadGrade
import com.roadwise.utils.RoadQualityScorer
import com.roadwise.utils.Severity
import com.roadwise.models.PotholeData
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Custom osmdroid overlay that renders a density-based heatmap using
 * the same grid bucketing logic from RoadQualityScorer, with color coded
 * radial gradients scaled by severity.
 */
class HeatmapOverlay(
    private val context: Context,
    private val potholes: List<PotholeData>
) : Overlay() {

    data class HeatCluster(
        val center: GeoPoint,
        val count: Int,
        val dominantGrade: RoadGrade,
        val score: Float
    )

    val clusters: List<HeatCluster>

    init {
        val segments = RoadQualityScorer.computeSegments(potholes)
        clusters = segments.map { seg ->
            val bb = seg.boundingBox
            val centerLat = (bb.latNorth + bb.latSouth) / 2.0
            val centerLon = (bb.lonEast  + bb.lonWest)  / 2.0
            HeatCluster(
                center        = GeoPoint(centerLat, centerLon),
                count         = seg.potholes.size,
                dominantGrade = seg.grade,
                score         = seg.score
            )
        }.sortedByDescending { it.count }
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val projection = mapView.projection

        for (cluster in clusters) {
            val screenPoint = projection.toPixels(cluster.center, null)

            // Radius scales with count (min 40px, max 120px)
            val baseRadius = (40 + cluster.count * 8).coerceIn(40, 120).toFloat()

            val centerColor = gradeToColor(cluster.dominantGrade, alpha = 0.65f)
            val edgeColor   = Color.TRANSPARENT

            val gradient = RadialGradient(
                screenPoint.x.toFloat(), screenPoint.y.toFloat(),
                baseRadius,
                intArrayOf(centerColor, edgeColor),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = gradient
            }
            canvas.drawCircle(
                screenPoint.x.toFloat(),
                screenPoint.y.toFloat(),
                baseRadius,
                paint
            )
        }
    }

    private fun gradeToColor(grade: RoadGrade, alpha: Float): Int {
        val alphaInt = (alpha * 255).toInt()
        return when (grade) {
            RoadGrade.A -> Color.argb(alphaInt, 0x2E, 0xCC, 0x71) // green
            RoadGrade.B -> Color.argb(alphaInt, 0x82, 0xE0, 0xAA) // light green
            RoadGrade.C -> Color.argb(alphaInt, 0xF4, 0xD0, 0x3F) // amber
            RoadGrade.D -> Color.argb(alphaInt, 0xE6, 0x7E, 0x22) // orange
            RoadGrade.F -> Color.argb(alphaInt, 0xE7, 0x4C, 0x3C) // red
        }
    }
}
