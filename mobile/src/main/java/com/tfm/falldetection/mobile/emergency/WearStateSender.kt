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
 * WearStateSender
 *
 * Envía al reloj el estado de pre-alerta para mantener la UI sincronizada
 * entre ambos dispositivos. Path único "/prealert_state" con los siguientes
 * payloads de texto plano:
 *
 *   - "START:<secs>" → ha comenzado la pre-alerta; el reloj inicia su
 *                      countdown local con <secs> segundos.
 *   - "END"          → la pre-alerta ha sido cancelada (por el usuario o
 *                      por reset).
 *   - "RESOLVED"     → el countdown expiró y el móvil ha enviado el SMS;
 *                      el reloj muestra la pantalla de aviso enviado.
 */
object WearStateSender {

    private const val TAG  = "WearStateSender"
    private const val PATH = "/prealert_state"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun sendStart(context: Context, totalSecs: Int)  = send(context, "START:$totalSecs")
    fun sendEnd(context: Context)                    = send(context, "END")
    fun sendResolved(context: Context)               = send(context, "RESOLVED")

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