package com.roadwise.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class GraphicOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val lock = Any()
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
    }

    private var rects = mutableListOf<RectF>()

    fun updateRects(newRects: List<Rect>, imageWidth: Int, imageHeight: Int) {
        synchronized(lock) {
            rects.clear()
            for (rect in newRects) {
                // Scale the coordinates from the camera image size to the View size
                val scaleX = width.toFloat() / imageHeight // Camera images are usually rotated
                val scaleY = height.toFloat() / imageWidth
                
                val scaledRect = RectF(
                    rect.left * scaleX,
                    rect.top * scaleY,
                    rect.right * scaleX,
                    rect.bottom * scaleY
                )
                rects.add(scaledRect)
            }
            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(lock) {
            for (rect in rects) {
                canvas.drawRect(rect, paint)
            }
        }
    }
}
