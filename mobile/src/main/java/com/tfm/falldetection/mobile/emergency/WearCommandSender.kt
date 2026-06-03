package com.tfm.falldetection.mobile.emergency

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * WearCommandSender
 *
 * Envía órdenes de control de monitorización al reloj. Path
 * "/monitoring_command" con payloads de texto:
 *
 *   - "PAUSE"  → el reloj detiene SensorCaptureService
 *   - "RESUME" → el reloj arranca SensorCaptureService
 *
 * Es independiente de WearStateSender (que gestiona el estado de la
 * pre-alerta) para mantener separadas ambas responsabilidades.
 */
object WearCommandSender {

    private const val TAG  = "WearCommandSender"
    private const val PATH = "/monitoring_command"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sendPause(context: Context)  = send(context, "PAUSE")
    fun sendResume(context: Context) = send(context, "RESUME")

    private fun send(context: Context, payload: String) {
        val ctx = context.applicationContext
        scope.launch {
            try {
                val nodes  = Wearable.getNodeClient(ctx).connectedNodes.await()
                val client = Wearable.getMessageClient(ctx)
                if (nodes.isEmpty()) {
                    Log.w(TAG, "Sin nodos conectados — '$payload' descartado")
                    return@launch
                }
                val bytes = payload.toByteArray(Charsets.UTF_8)
                nodes.forEach { node ->
                    client.sendMessage(node.id, PATH, bytes).await()
                    Log.i(TAG, "Enviado '$payload' a ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando '$payload': ${e.message}", e)
            }
        }
    }
}