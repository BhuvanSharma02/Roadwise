package com.roadwise.mapping

import android.content.Context
import android.graphics.*
import androidx.core.graphics.ColorUtils
import com.roadwise.models.PotholeData
import com.roadwise.sensors.RoadFeature
import com.roadwise.utils.PotholeRepository
import com.roadwise.utils.RoadQualityScorer
import com.roadwise.utils.RoadSegment
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class AdaptiveRoadOverlay(
    private val context: Context,
    private val repository: PotholeRepository
) : Overlay() {

    // Zoom threshold that switches between the two views
    private val ZOOM_THRESHOLD = 15.0

    private var segments: List<RoadSegment> = emptyList()
    private var heatmapPoints: List<PotholeData> = emptyList()
    private var overlayAlpha: Int = 255

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setAlpha(value: Int) {
        this.overlayAlpha = value
    }

    fun refresh() {
        val allData = repository.getAllPotholes(context)
        heatmapPoints = allData
        segments      = RoadQualityScorer.computeSegments(allData)
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val zoom = mapView.zoomLevelDouble

        if (zoom < ZOOM_THRESHOLD) {
            drawSegmentGrades(canvas, mapView)
        } else {
            drawHeatmapPoints(canvas, mapView)
        }
    }

    // ── Zoomed-out: color-coded grid cells ─────────────────────────────────
    private fun drawSegmentGrades(canvas: Canvas, mapView: MapView) {
        val proj = mapView.projection
        segments.forEach { seg ->
            val topLeft     = proj.toPixels(GeoPoint(seg.boundingBox.latNorth, seg.boundingBox.lonWest), null)
            val bottomRight = proj.toPixels(GeoPoint(seg.boundingBox.latSouth, seg.boundingBox.lonEast), null)

            val rect = RectF(
                topLeft.x.toFloat(),     topLeft.y.toFloat(),
                bottomRight.x.toFloat(), bottomRight.y.toFloat()
            )

            // Filled cell with 55% opacity (scaled by overlayAlpha) so map tiles show through
            val baseAlpha = 140
            val finalAlpha = (baseAlpha * (overlayAlpha / 255f)).toInt()
            segmentPaint.color = ColorUtils.setAlphaComponent(seg.grade.color, finalAlpha)
            canvas.drawRect(rect, segmentPaint)

            // Grade letter label — only draw if cell is large enough to be legible
            if (rect.width() > 40f) {
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color     = Color.WHITE
                    alpha     = overlayAlpha
                    textSize  = rect.width().coerceIn(14f, 28f)
                    textAlign = Paint.Align.CENTER
                    typeface  = Typeface.DEFAULT_BOLD
                    setShadowLayer(3f, 1f, 1f, Color.BLACK)
                }
                canvas.drawText(seg.grade.label.take(1), rect.centerX(), rect.centerY() + textPaint.textSize / 3, textPaint)
            }
        }
    }

    // ── Zoomed-in: individual heatmap blobs ─────────────────────────────────
    private fun drawHeatmapPoints(canvas: Canvas, mapView: MapView) {
        val proj = mapView.projection
        heatmapPoints.forEach { point ->
            val pixel = proj.toPixels(point.location, null)

            // Outer glow radius scales with intensity
            val radius = (20f + point.intensity * 10f).coerceIn(20f, 55f)
            val heatColor = getHeatColor(point.type)

            val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    pixel.x.toFloat(), pixel.y.toFloat(), radius,
                    intArrayOf(
                        ColorUtils.setAlphaComponent(heatColor, (200 * (overlayAlpha / 255f)).toInt()),
                        ColorUtils.setAlphaComponent(heatColor, (60 * (overlayAlpha / 255f)).toInt()),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), radius, gradientPaint)
        }
    }

    private fun getHeatColor(type: RoadFeature): Int = when (type) {
        RoadFeature.POTHOLE    -> 0xFFE74C3C.toInt()  // red
        RoadFeature.SPEED_BUMP -> 0xFF1ABC9C.toInt()  // teal
        else                   -> 0xFF94A3B8.toInt()  // slate
    }
}
