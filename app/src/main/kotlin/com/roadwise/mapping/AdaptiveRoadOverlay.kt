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
        drawHeatmapPoints(canvas, mapView)
    }

    // ── Individual heatmap blobs with pulse effect ───────────────
    private fun drawHeatmapPoints(canvas: Canvas, mapView: MapView) {
        val proj = mapView.projection
        val time = android.os.SystemClock.uptimeMillis()
        val pulseCycle = (time % 2000L).toFloat() / 2000f // 0f to 1f over 2 seconds

        heatmapPoints.forEach { point ->
            val pixel = proj.toPixels(point.location, null)
            val baseRadius = (15f + point.intensity * 5f).coerceIn(15f, 30f)
            val heatColor = getHeatColor(point.type)

            // Core circle
            segmentPaint.color = heatColor
            segmentPaint.alpha = (overlayAlpha * 0.8f).toInt()
            segmentPaint.style = Paint.Style.FILL
            canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), baseRadius, segmentPaint)

            // Pulse ring
            val pulseRadius = baseRadius + (baseRadius * 2f * pulseCycle)
            val pulseAlpha = ((1f - pulseCycle) * overlayAlpha * 0.5f).toInt()
            segmentPaint.alpha = pulseAlpha.coerceIn(0, 255)
            segmentPaint.style = Paint.Style.STROKE
            segmentPaint.strokeWidth = 4f
            canvas.drawCircle(pixel.x.toFloat(), pixel.y.toFloat(), pulseRadius, segmentPaint)
            
            // Reset style back to fill
            segmentPaint.style = Paint.Style.FILL
        }
        
        // Ensure the map continually redraws to keep the animation running smoothly when zoomed in
        if (heatmapPoints.isNotEmpty()) {
            mapView.postInvalidateOnAnimation()
        }
    }

    private fun getHeatColor(type: RoadFeature): Int = when (type) {
        RoadFeature.POTHOLE    -> 0xFFE74C3C.toInt()  // red
        RoadFeature.SPEED_BUMP -> 0xFF1ABC9C.toInt()  // teal
        else                   -> 0xFF94A3B8.toInt()  // slate
    }
}
