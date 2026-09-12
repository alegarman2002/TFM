package com.tfm.falldetection.mobile

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * InferenceEngine
 *
 * Motor de inferencia del modelo TFLite de detección de caídas.
 *
 * El modelo entrenado recibe un tensor [1, 150, 4]:
 *   - 150 muestras (3 segundos a 50 Hz)
 *   - 4 canales: [acc_x, acc_y, acc_z, magnitud]
 *
 * El smartwatch envía solo (x, y, z) en m/s² (1800 bytes por ventana).
 * Aquí hacemos el preprocesado:
 *   1) Convertir m/s² → g (dividir por 9.81)
 *   2) Calcular magnitud = sqrt(x² + y² + z²) en g
 *   3) Normalizar Z-score con los parámetros del entrenamiento
 *   4) Pasar el tensor [1, 150, 4] al modelo
 */
class InferenceEngine private constructor(context: Context) {

    companion object {
        private const val TAG       = "InferenceEngine"
        private const val MODEL_FILE = "fall_detection_model.tflite"

        const val WINDOW_SIZE        = 150
        const val N_INPUT_AXES       = 3   // x, y, z (lo que envía el reloj)
        const val N_MODEL_CHANNELS   = 4   // x, y, z, magnitud (lo que espera el modelo)
        const val PAYLOAD_BYTES      = WINDOW_SIZE * N_INPUT_AXES * Float.SIZE_BYTES  // 1800

        /** Conversión de m/s² a g. */
        private const val GRAVITY    = 9.81f

        /** Umbral de clasificación calibrado durante el entrenamiento. */
        const val UMBRAL_CAIDA       = 0.5f

        /**
         * Parámetros de normalización Z-score del modelo entrenado.
         * Generados por train_model.py — ver output/normalization_params.txt.
         *
         * Orden: [acc_x, acc_y, acc_z, magnitud]
         */
        private val NORM_MEAN = floatArrayOf(-0.08176608f, -0.08176608f, -0.08176608f, 1.06262231f)
        private val NORM_STD  = floatArrayOf( 0.65945917f,  0.65945917f,  0.65945917f, 0.44220865f)

        @Volatile private var instance: InferenceEngine? = null

        fun getInstance(context: Context): InferenceEngine {
            return instance ?: synchronized(this) {
                instance ?: InferenceEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val interpreter: Interpreter

    init {
        val modelBuffer = loadModelFile(context)
        val options = Interpreter.Options().apply { setNumThreads(2) }
        interpreter = Interpreter(modelBuffer, options)

        val inputShape  = interpreter.getInputTensor(0).shape()
        val outputShape = interpreter.getOutputTensor(0).shape()
        Log.i(TAG, "Modelo TFLite cargado")
        Log.i(TAG, "Input shape:  ${inputShape.joinToString(", ", "[", "]")}")
        Log.i(TAG, "Output shape: ${outputShape.joinToString(", ", "[", "]")}")
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(fd.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }

    /**
     * Ejecuta la inferencia sobre un payload del reloj.
     *
     * @param sensorPayload 1800 bytes = 150 muestras × 3 floats (x, y, z) en m/s²
     * @return Probabilidad de caída ∈ [0, 1]
     */
    fun ejecutarInferencia(sensorPayload: ByteArray): Float {
        if (sensorPayload.size != PAYLOAD_BYTES) {
            Log.e(TAG, "Payload incorrecto: ${sensorPayload.size} bytes (esperados $PAYLOAD_BYTES)")
            return 0f
        }

        val inputTensor = preprocessInput(sensorPayload)
        val outputArray = Array(1) { FloatArray(1) }

        val t0 = System.currentTimeMillis()
        interpreter.run(inputTensor, outputArray)
        val latencyMs = System.currentTimeMillis() - t0

        val probability = outputArray[0][0]
        Log.v(TAG, "Inferencia: p=${"%.4f".format(probability)} (${latencyMs}ms)")
        return probability
    }

    /**
     * Convierte el ByteArray bruto en un tensor [1, 150, 4] normalizado:
     *   1. Lee (x, y, z) en m/s²
     *   2. Divide por 9.81 → g
     *   3. Calcula magnitud = √(x² + y² + z²)
     *   4. Aplica Z-score por canal
     */
    private fun preprocessInput(payload: ByteArray): Array<Array<FloatArray>> {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val tensor = Array(1) { Array(WINDOW_SIZE) { FloatArray(N_MODEL_CHANNELS) } }

        for (t in 0 until WINDOW_SIZE) {
            // 1. Leer y convertir a g
            val xG = buffer.float / GRAVITY
            val yG = buffer.float / GRAVITY
            val zG = buffer.float / GRAVITY

            // 2. Calcular magnitud
            val magG = sqrt(xG * xG + yG * yG + zG * zG)

            // 3. Normalizar (Z-score por canal)
            tensor[0][t][0] = (xG  - NORM_MEAN[0]) / NORM_STD[0]
            tensor[0][t][1] = (yG  - NORM_MEAN[1]) / NORM_STD[1]
            tensor[0][t][2] = (zG  - NORM_MEAN[2]) / NORM_STD[2]
            tensor[0][t][3] = (magG - NORM_MEAN[3]) / NORM_STD[3]
        }
        return tensor
    }

    fun close() {
        interpreter.close()
        instance = null
    }
}