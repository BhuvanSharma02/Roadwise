package com.roadwise.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import java.io.BufferedReader
import java.io.InputStreamReader

class PotholeAnalyzer(
    context: Context,
    private val onObjectsDetected: (List<Rect>, Int, Int, Float) -> Unit
) : ImageAnalysis.Analyzer {

    private val labels = loadLabels(context)

    private val localModel = LocalModel.Builder()
        .setAssetFilePath("pothole_model.tflite")
        .build()

    private val options = CustomObjectDetectorOptions.Builder(localModel)
        .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
        .enableClassification() 
        .setClassificationConfidenceThreshold(0.4f) // Lowered slightly for better detection
        .setMaxPerObjectLabelCount(1)
        .build()

    private val detector = ObjectDetection.getClient(options)

    private fun loadLabels(context: Context): List<String> {
        val list = mutableListOf<String>()
        try {
            val reader = BufferedReader(InputStreamReader(context.assets.open("pothole_labels.txt")))
            var line: String? = reader.readLine()
            while (line != null) {
                // Remove numbers if they exist (e.g. "0 potholes" -> "potholes")
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
                    // Filter: Only include objects that are explicitly identified as potholes
                    val potholeRects = detectedObjects.filter { obj ->
                        val label = obj.labels.firstOrNull()
                        val labelText = label?.text?.lowercase() ?: ""
                        
                        // Only match the "potholes" label and explicitly ignore "no potholes"
                        // This prevents drawing boxes on clear road segments.
                        val isPothole = (labelText.contains("potholes") && !labelText.contains("no")) || 
                                        labelText == "pothole"
                        
                        val hasHighConfidence = (label?.confidence ?: 0f) > 0.45f
                        
                        isPothole && hasHighConfidence
                    }.map { it.boundingBox }
                    
                    val maxConfidence = detectedObjects.maxOfOrNull { 
                        it.labels.firstOrNull()?.confidence ?: 0.5f 
                    } ?: 0f
                    
                    onObjectsDetected(potholeRects, imageProxy.width, imageProxy.height, maxConfidence)
                }
                .addOnFailureListener {
                    // Handle failure
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
