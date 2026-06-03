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
 * CancelSender
 *
 * Utilidad de un solo método para enviar al móvil la petición de cancelar
 * la pre-alerta en curso. Se invoca desde el botón "ESTOY BIEN" de la
 * pantalla de pre-alerta del reloj.
 *
 * Path: "/cancel_alert" — payload vacío (basta con la llegada del mensaje).
 */
object CancelSender {

    private const val TAG = "CancelSender"
    private const val PATH = "/cancel_alert"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun send(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            try {
                val nodes  = Wearable.getNodeClient(ctx).connectedNodes.await()
                val client = Wearable.getMessageClient(ctx)
                if (nodes.isEmpty()) {
                    Log.w(TAG, "Sin nodos conectados — cancel no entregable")
                    return@launch
                }
                nodes.forEach { node ->
                    client.sendMessage(node.id, PATH, ByteArray(0)).await()
                    Log.i(TAG, "Cancelación enviada a ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando cancelación: ${e.message}", e)
            }
        }
    }
}