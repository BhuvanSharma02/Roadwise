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
     * @return Detected class index: 0 (Smooth), 1 (Bump), 2 (Pothole)
     */
    fun predict(features: FloatArray): Int {
        if (features.size != 12) return 0

        // Create input tensor [1, 12]
        val inputName = session.inputNames.iterator().next()
        val shape = longArrayOf(1, 12)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape)

        val result = session.run(Collections.singletonMap(inputName, tensor))
        
        // Output from skl2onnx with zipmap=False is typically:
        // [label_tensor, probability_tensor]
        // result[0] is labels (int64[]), result[1] is probabilities (float[][])
        
        val label = try {
            val labelTensor = result.get(0) as OnnxTensor
            (labelTensor.value as LongArray)[0].toInt()
        } catch (e: Exception) {
            Log.e("RoadModelInference", "Error extracting prediction label from tensor", e)
            0 // Fallback to smooth road
        } finally {
            result.close()
            tensor.close()
        }
        
        return label
    }

    fun close() {
        session.close()
        env.close()
    }
}
