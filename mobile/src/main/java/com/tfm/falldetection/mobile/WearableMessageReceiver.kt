package com.tfm.falldetection.mobile

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.tfm.falldetection.mobile.data.DataCollectionStore
import com.tfm.falldetection.mobile.emergency.MonitoringController
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WearableMessageReceiver
 *
 * Punto de entrada de la app móvil desde la Wearable Data Layer API. Paths:
 *
 *   - /sensor_data      → trama de 1800 bytes con la ventana del acelerómetro.
 *                         Si la monitorización está pausada se descarta.
 *   - /cancel_alert     → cancelación enviada desde el reloj.
 *   - /data_collection  → (=== RECOGIDA DE DATOS ===) lote continuo de muestras
 *                         para el dataset de entrenamiento. Formato binario
 *                         little-endian:
 *                           Long startSampleIndex | Int count |
 *                           count × (Long ts | Float ax | Float ay | Float az)
 */
class WearableMessageReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "WearableMsgReceiver"

        private const val PATH_SENSOR_DATA     = "/sensor_data"
        private const val PATH_CANCEL_ALERT    = "/cancel_alert"
        private const val PATH_DATA_COLLECTION = "/data_collection"   // === RECOGIDA DE DATOS ===

        private const val EXPECTED_BYTES = 150 * 3 * 4   // 1800
        private const val FALL_THRESHOLD = 0.5f
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_SENSOR_DATA     -> handleSensorData(event)
            PATH_CANCEL_ALERT    -> handleCancelAlert()
            PATH_DATA_COLLECTION -> handleDataCollection(event)   // === RECOGIDA DE DATOS ===
            else                 -> super.onMessageReceived(event)
        }
    }

    private fun handleSensorData(event: MessageEvent) {
        if (!MonitoringController.isProtected.value) {
            Log.d(TAG, "Monitorización pausada — trama ignorada")
            return
        }

        val bytes = event.data
        if (bytes.size != EXPECTED_BYTES) {
            Log.w(TAG, "Tamaño incorrecto: ${bytes.size} (esperado $EXPECTED_BYTES)")
            return
        }
        Log.v(TAG, "Trama recibida: ${bytes.size} bytes")

        val probability = try {
            InferenceEngine.getInstance(this).ejecutarInferencia(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error en inferencia: ${e.message}", e)
            return
        }
        Log.d(TAG, "Probabilidad de caída: $probability")

        if (probability >= FALL_THRESHOLD) {
            Log.w(TAG, "⚠️ CAÍDA DETECTADA (p=$probability)")
            EmergencyManager.getInstance(this).onCaidaDetectada(probability)
        }
    }

    private fun handleCancelAlert() {
        Log.w(TAG, "Cancelación recibida desde el reloj")
        EmergencyManager.getInstance(this).cancelar()
    }

    // === RECOGIDA DE DATOS ===
    private fun handleDataCollection(event: MessageEvent) {
        if (!DataCollectionStore.isActive()) return

        try {
            val buf = ByteBuffer.wrap(event.data).order(ByteOrder.LITTLE_ENDIAN)
            val startIndex = buf.long
            val count = buf.int
            for (i in 0 until count) {
                val ts = buf.long
                val ax = buf.float
                val ay = buf.float
                val az = buf.float
                DataCollectionStore.appendSample(startIndex + i, ts, ax, ay, az)
            }
            Log.v(TAG, "Lote recogida: $count muestras desde índice $startIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando lote de recogida: ${e.message}", e)
        }
    }
    // === FIN RECOGIDA DE DATOS ===
}