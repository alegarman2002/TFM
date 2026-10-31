package com.tfm.falldetection.mobile.emergency

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * SmsSender
 *
 * Envía un SMS al contacto de emergencia con la información de la caída.
 *
 * El mensaje incluye:
 *   - Aviso explícito de que es una alerta automática
 *   - Coordenadas GPS (si están disponibles)
 *   - Dirección textual (si se ha podido resolver)
 *   - Enlace de Google Maps para abrir la ubicación con un clic
 *
 * Si el cuerpo del SMS supera 160 caracteres, SmsManager lo divide
 * automáticamente en multipart.
 */
object SmsSender {

    private const val TAG = "SmsSender"

    /** Comprueba si la app puede enviar SMS sin lanzar SecurityException. */
    fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Construye el cuerpo del mensaje a partir del nombre, coordenadas
     * y dirección textual opcional.
     */
    fun buildMessageBody(
        contactName: String,
        latitude: Double?,
        longitude: Double?,
        address: String?
    ): String {
        val saludo = if (contactName.isNotBlank()) "Hola $contactName. " else ""
        val cabecera = "${saludo}ALERTA AUTOMÁTICA: posible caída detectada."

        val ubicacion = when {
            latitude != null && longitude != null -> {
                val coords  = "Coordenadas: %.6f, %.6f".format(latitude, longitude)
                val mapsUrl = "https://maps.google.com/?q=$latitude,$longitude"
                val direc   = address?.let { "\nDirección: $it" }.orEmpty()
                "\n$coords$direc\nUbicación en mapa: $mapsUrl"
            }
            else -> "\n(Ubicación no disponible — revisa al usuario inmediatamente.)"
        }

        return "$cabecera$ubicacion"
    }

    /** Deja el texto en el alfabeto GSM de 7 bits: sin tildes ni rayas largas. */
    private fun paraSms(texto: String): String =
        java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('—', '-')
            .replace('–', '-')

    /**
     * Envía el SMS. Devuelve true si la llamada al sistema se realizó sin
     * excepción. No garantiza la entrega real al destinatario (depende
     * de la cobertura y del operador).
     *
     * @param context  Necesario para acceder a SmsManager en API 31+.
     * @param phone    Teléfono del destinatario (validado previamente).
     * @param body     Cuerpo del mensaje a enviar.
     */
    fun send(context: Context, phone: String, body: String): Boolean {
        if (!hasSmsPermission(context)) {
            Log.e(TAG, "Sin permiso SEND_SMS — abortado")
            return false
        }
        if (phone.isBlank()) {
            Log.e(TAG, "Número de teléfono vacío — abortado")
            return false
        }

        return try {
            val smsManager: SmsManager =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

            val texto = paraSms(body)
            val parts = try {
                smsManager.divideMessage(texto)
            } catch (e: SecurityException) {
                // En algunos Samsung, divideMessage falla con texto que no es del alfabeto GSM
                ArrayList(texto.chunked(67))
            }
            if (parts.size == 1) {
                smsManager.sendTextMessage(phone, null, texto, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            }

            Log.i(TAG, "SMS enviado a $phone (${parts.size} parte/s)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar SMS: ${e.message}", e)
            false
        }
    }
}