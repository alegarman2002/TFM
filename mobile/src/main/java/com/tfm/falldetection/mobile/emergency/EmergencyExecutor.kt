package com.tfm.falldetection.mobile.emergency

import android.content.Context
import android.util.Log
import com.tfm.falldetection.mobile.EmergencyManager
import com.tfm.falldetection.mobile.data.EmergencyPreferences
import kotlinx.coroutines.flow.first

/**
 * EmergencyExecutor
 *
 * Conecta el EmergencyManager con los servicios reales del sistema
 * (LocationProvider + SmsSender + EmergencyPreferences).
 *
 * Se inicializa una sola vez al arrancar la MainActivity y registra
 * el callback `onSendAlert` del EmergencyManager. A partir de ese
 * momento, cuando el countdown expire, se ejecutará automáticamente
 * la siguiente cadena:
 *
 *   1) Leer el contacto configurado.
 *   2) Pedir la ubicación (timeout 8 s).
 *   3) Resolver dirección textual mediante Geocoder inverso (timeout 3 s).
 *   4) Construir el cuerpo del SMS.
 *   5) Enviar el SMS.
 *   6) Resetear el EmergencyManager a PROTEGIDO tras unos segundos.
 *
 * Si en algún paso falla la obtención de ubicación, el SMS se envía
 * igualmente con el aviso de "ubicación no disponible".
 */
object EmergencyExecutor {

    private const val TAG = "EmergencyExecutor"

    private var initialized = false

    /**
     * Llamado desde MainActivity.onCreate(). Idempotente — si ya está
     * inicializado, no hace nada.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val ctx = context.applicationContext
        val manager = EmergencyManager.getInstance(ctx)

        manager.onSendAlert = { probability ->
            executeAlert(ctx, probability)
        }

        Log.i(TAG, "EmergencyExecutor inicializado")
    }

    /**
     * Cadena completa de envío de alerta. Se ejecuta dentro del scope
     * del EmergencyManager (Dispatchers.Default por defecto).
     */
    private suspend fun executeAlert(context: Context, probability: Float) {
        Log.w(TAG, "Ejecutando alerta (p=$probability)")

        // 1. Leer contacto configurado
        val settings = EmergencyPreferences(context).settings.first()
        if (!settings.isConfigured) {
            Log.e(TAG, "No hay contacto configurado — abortando envío")
            EmergencyManager.getInstance(context).reset()
            return
        }

        // 2-3. Obtener ubicación + dirección (puede ser null si no hay permiso o falla)
        val location = LocationProvider.getLocationInfo(context)

        // 4. Construir mensaje
        val body = SmsSender.buildMessageBody(
            contactName = settings.contactName,
            latitude    = location?.latitude,
            longitude   = location?.longitude,
            address     = location?.address
        )

        Log.i(TAG, "Cuerpo del SMS:\n$body")

        // 5. Enviar SMS
        val ok = SmsSender.send(context, settings.contactPhone, body)
        if (!ok) {
            Log.e(TAG, "Fallo al enviar SMS — alerta no entregada")
        }

        // 6. Volver al estado protegido tras una pausa para que la UI muestre
        // el estado RESOLUCION_SOS durante unos segundos
        kotlinx.coroutines.delay(5_000L)
        EmergencyManager.getInstance(context).reset()
    }
}