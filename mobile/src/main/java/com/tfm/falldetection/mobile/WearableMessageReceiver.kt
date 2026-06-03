package com.tfm.falldetection.mobile

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.tfm.falldetection.mobile.emergency.MonitoringController

/**
 * WearableMessageReceiver
 *
 * Punto de entrada de la app móvil desde la Wearable Data Layer API.
 * Maneja dos paths:
 *
 *   - /sensor_data   → trama de 1800 bytes con la ventana del acelerómetro.
 *                      Si la monitorización está pausada (MonitoringController),
 *                      la trama se descarta sin procesar. En caso contrario se
 *                      ejecuta la inferencia y, si supera el umbral, se dispara
 *                      la pre-alerta.
 *
 *   - /cancel_alert  → cancelación enviada desde el reloj; aborta la
 *                      pre-alerta en curso.
 */
class WearableMessageReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "WearableMsgReceiver"

        private const val PATH_SENSOR_DATA  = "/sensor_data"
        private const val PATH_CANCEL_ALERT = "/cancel_alert"

        private const val EXPECTED_BYTES = 150 * 3 * 4   // 1800
        private const val FALL_THRESHOLD = 0.5f
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_SENSOR_DATA  -> handleSensorData(event)
            PATH_CANCEL_ALERT -> handleCancelAlert()
            else              -> super.onMessageReceived(event)
        }
    }

    private fun handleSensorData(event: MessageEvent) {
        // Si la monitorización está pausada desde el móvil, ignorar la trama
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
}