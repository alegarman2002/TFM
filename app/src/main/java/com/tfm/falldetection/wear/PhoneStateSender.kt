package com.tfm.falldetection.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * PhoneStateSender
 *
 * Avisa al teléfono de que el usuario ha activado o detenido la vigilancia
 * desde el reloj, para que el escudo del teléfono refleje el mismo estado.
 * Path "/monitoring_state" con los payloads "ACTIVE" o "PAUSED".
 */
object PhoneStateSender {

    private const val TAG  = "PhoneStateSender"
    private const val PATH = "/monitoring_state"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun send(context: Context, activo: Boolean) {
        val ctx = context.applicationContext
        val payload = if (activo) "ACTIVE" else "PAUSED"
        scope.launch {
            try {
                val nodes  = Wearable.getNodeClient(ctx).connectedNodes.await()
                val client = Wearable.getMessageClient(ctx)
                if (nodes.isEmpty()) {
                    Log.w(TAG, "Sin nodos conectados — estado '$payload' descartado")
                    return@launch
                }
                nodes.forEach { node ->
                    client.sendMessage(node.id, PATH, payload.toByteArray(Charsets.UTF_8)).await()
                    Log.i(TAG, "Estado '$payload' enviado a ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando estado '$payload': ${e.message}", e)
            }
        }
    }
}