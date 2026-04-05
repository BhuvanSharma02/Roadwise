package com.roadwise.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object ImageAnalyzer {

    // Returns the path of the clearest image out of the list
    fun getClearestImagePath(paths: List<String>): String? {
        if (paths.isEmpty()) return null
        if (paths.size == 1) return paths[0]

        var bestPath = paths[0]
        var maxSharpness = -1.0

        for (path in paths) {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)
            
            options.inSampleSize = Math.max(1, Math.max(options.outWidth / 300, options.outHeight / 300))
            options.inJustDecodeBounds = false
            
            val bitmap = BitmapFactory.decodeFile(path, options) ?: continue
            val sharpness = calculateSharpness(bitmap)
            
            if (sharpness > maxSharpness) {
                maxSharpness = sharpness
                bestPath = path
            }
            bitmap.recycle()
        }
        return bestPath
    }

    // Returns the clearest bitmap out of the list without needing paths
    fun getClearestBitmap(bitmaps: List<Bitmap>): Bitmap? {
        if (bitmaps.isEmpty()) return null
        if (bitmaps.size == 1) return bitmaps[0]

        var bestBitmap = bitmaps[0]
        var maxSharpness = -1.0

        for (bitmap in bitmaps) {
            val sharpness = calculateSharpnessScaled(bitmap)
            if (sharpness > maxSharpness) {
                maxSharpness = sharpness
                bestBitmap = bitmap
            }
        }
        return bestBitmap
    }

    private fun calculateSharpnessScaled(bitmap: Bitmap): Double {
        val targetSize = 300
        val scale = targetSize.toFloat() / Math.max(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, false)
        val sharpness = calculateSharpness(scaled)
        if (scaled != bitmap) {
            scaled.recycle()
        }
        return sharpness
    }

    private fun calculateSharpness(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        
        // Convert to grayscale
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        
        // Laplacian 3x3 Filter Variance
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = gray[i - w] + gray[i + w] + gray[i - 1] + gray[i + 1] - 4 * gray[i]
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }
}
