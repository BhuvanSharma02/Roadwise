package com.roadwise.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer

class PotholeAnalyzer(
    context: Context,
    private val onObjectsDetected: (List<Rect>, Int, Int, Float, List<Bitmap>) -> Unit
) : ImageAnalysis.Analyzer {

    private val labels = loadLabels(context)
    private var isCapturingBurst = false
    private val burstCount = 3
    private val capturedBitmaps = mutableListOf<Bitmap>()

    private val localModel = LocalModel.Builder()
        .setAssetFilePath("pothole_model.tflite")
        .build()

    private val options = CustomObjectDetectorOptions.Builder(localModel)
        .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
        .enableClassification() 
        .setClassificationConfidenceThreshold(0.4f) 
        .setMaxPerObjectLabelCount(1)
        .build()

    private val detector = ObjectDetection.getClient(options)

    private fun loadLabels(context: Context): List<String> {
        val list = mutableListOf<String>()
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open("pothole_labels.txt")))
            var line: String? = reader.readLine()
            while (line != null) {
                val cleanLabel = line.replace(Regex("[0-9]"), "").trim().lowercase()
                list.add(cleanLabel)
                line = reader.readLine()
            }
            reader.close()
        } catch (e: Exception) {
            list.add("potholes")
            list.add("pothole")
        }
        return list
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { detectedObjects ->
                    val potholes = detectedObjects.filter { obj ->
                        val label = obj.labels.firstOrNull()
                        val labelText = label?.text?.lowercase() ?: ""
                        val isPothole = (labelText.contains("potholes") && !labelText.contains("no")) || 
                                        labelText == "pothole"
                        val hasHighConfidence = (label?.confidence ?: 0f) > 0.45f
                        isPothole && hasHighConfidence
                    }

                    val potholeRects = potholes.map { it.boundingBox }
                    val maxConfidence = detectedObjects.maxOfOrNull { 
                        it.labels.firstOrNull()?.confidence ?: 0.5f 
                    } ?: 0f
                    
                    if (potholes.isNotEmpty() && !isCapturingBurst) {
                        startBurstCapture(imageProxy)
                    } else if (isCapturingBurst && capturedBitmaps.size < burstCount) {
                        capturedBitmaps.add(imageProxyToBitmap(imageProxy))
                    }

                    val bitmapsToReturn = if (isCapturingBurst && capturedBitmaps.size >= burstCount) {
                        val result = capturedBitmaps.toList()
                        capturedBitmaps.clear()
                        isCapturingBurst = false
                        result
                    } else {
                        emptyList()
                    }

                    onObjectsDetected(potholeRects, imageProxy.width, imageProxy.height, maxConfidence, bitmapsToReturn)
                }
                .addOnFailureListener {
                    Log.e("RoadWise-ML", "Detection failed", it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun startBurstCapture(imageProxy: ImageProxy) {
        isCapturingBurst = true
        capturedBitmaps.clear()
        capturedBitmaps.add(imageProxyToBitmap(imageProxy))
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val bitmap = image.toBitmap()
        val matrix = Matrix()
        matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
