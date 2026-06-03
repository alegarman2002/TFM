package com.tfm.falldetection.wear

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * PhoneMessageReceiver
 *
 * Escucha los mensajes que envía el móvil al reloj. Maneja dos paths:
 *
 *   - /prealert_state    → sincroniza la pantalla de pre-alerta
 *                          (START:<secs> / END / RESOLVED).
 *
 *   - /monitoring_command→ control remoto de la captura:
 *                          "PAUSE"  detiene SensorCaptureService
 *                          "RESUME" arranca SensorCaptureService
 *
 * El comando de monitorización es lógica de fondo: no altera la interfaz
 * del reloj, solo arranca o detiene el servicio de captura para ahorrar
 * batería cuando el usuario pausa desde el móvil.
 */
class PhoneMessageReceiver : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneMessageReceiver"
        private const val PATH_PREALERT_STATE     = "/prealert_state"
        private const val PATH_MONITORING_COMMAND = "/monitoring_command"
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_PREALERT_STATE     -> handlePreAlertState(event)
            PATH_MONITORING_COMMAND -> handleMonitoringCommand(event)
            else                    -> super.onMessageReceived(event)
        }
    }

    private fun handlePreAlertState(event: MessageEvent) {
        val payload = String(event.data, Charsets.UTF_8)
        Log.i(TAG, "prealert_state: '$payload'")

        val parts = payload.split(":")
        when (parts[0]) {
            "START" -> {
                val secs = parts.getOrNull(1)?.toIntOrNull() ?: 10
                WearAlertState.startPreAlert(this, secs)
                openMainActivity()
            }
            "END"      -> WearAlertState.endPreAlert()
            "RESOLVED" -> WearAlertState.setResolved(this)
        }
    }

    private fun handleMonitoringCommand(event: MessageEvent) {
        val payload = String(event.data, Charsets.UTF_8)
        Log.i(TAG, "monitoring_command: '$payload'")

        when (payload) {
            "PAUSE"  -> SensorCaptureService.stop(this)
            "RESUME" -> SensorCaptureService.start(this)
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir MainActivity: ${e.message}")
        }
    }
}