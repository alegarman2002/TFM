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
 * Envía órdenes de control al reloj por el path "/monitoring_command":
 *
 *   - "PAUSE"  / "RESUME"            → pausa/reanuda la monitorización
 *   - "START_COLLECTION" / "STOP_COLLECTION"
 *                                    → activa/desactiva el streaming continuo
 *                                      del acelerómetro para recogida de datos
 *                                      (=== RECOGIDA DE DATOS ===)
 */
object WearCommandSender {

    private const val TAG  = "WearCommandSender"
    private const val PATH = "/monitoring_command"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sendPause(context: Context)  = send(context, "PAUSE")
    fun sendResume(context: Context) = send(context, "RESUME")

    // === RECOGIDA DE DATOS ===
    fun sendStartCollection(context: Context) = send(context, "START_COLLECTION")
    fun sendStopCollection(context: Context)  = send(context, "STOP_COLLECTION")
    // === FIN RECOGIDA DE DATOS ===

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