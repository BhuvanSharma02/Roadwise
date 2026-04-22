package com.roadwise.sensors

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.*
import android.util.Log

class RoadModelInference(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        // Load the model from assets
        val modelBytes = context.assets.open("road_model.onnx").readBytes()
        session = env.createSession(modelBytes)
    }

    /**
     * Runs inference on the 12-feature vector.
     * @param features FloatArray of 12 features in order:
     * [z_mean, z_std, z_max, z_min, z_peak_to_peak, z_rms, x_std, y_std, z_energy, skew, kurtosis, impact_ratio]
     * @return Pair of Detected class index (0=Smooth, 1=Bump, 2=Pothole) and its confidence score
     */
    fun predict(features: FloatArray): Pair<Int, Float> {
        if (features.size != 12) return Pair(0, 0f)

        // Create input tensor [1, 12]
        val inputName = session.inputNames.iterator().next()
        val shape = longArrayOf(1, 12)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape)

        val result = session.run(Collections.singletonMap(inputName, tensor))
        
        // Output from skl2onnx with zipmap=False is typically:
        // [label_tensor, probability_tensor]
        // result[0] is labels (int64[]), result[1] is probabilities (float[][])
        
        var label = 0
        var confidence = 0f
        try {
            val labelTensor = result.get(0) as OnnxTensor
            val labelObj = labelTensor.value
            label = when (labelObj) {
                is LongArray -> labelObj[0].toInt()
                is IntArray -> labelObj[0]
                is Array<*> -> (labelObj[0] as? Number)?.toInt() ?: 0
                else -> 0
            }

            val probTensor = result.get(1) as OnnxTensor
            val probObj = probTensor.value
            if (probObj is Array<*> && probObj.firstOrNull() is FloatArray) {
                val probs = probObj[0] as FloatArray
                if (label >= 0 && label < probs.size) {
                    confidence = probs[label]
                }
            } else if (probObj is FloatArray) {
                if (label >= 0 && label < probObj.size) {
                    confidence = probObj[label]
                }
            }
        } catch (e: Exception) {
            Log.e("RoadModelInference", "Error extracting prediction", e)
        } finally {
            result.close()
            tensor.close()
        }
        
        return Pair(label, confidence)
    }

    private var isClosed = false

    fun close() {
        if (isClosed) return
        try {
            session.close()
            env.close()
            isClosed = true
        } catch (e: Exception) {
            Log.e("RoadModelInference", "Error closing ONNX session", e)
        }
    }
}
