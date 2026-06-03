package com.tfm.falldetection.mobile.data

/**
 * EmergencyEvent
 *
 * Representa un evento de caída detectada por el sistema.
 * Se utiliza para registrar la trazabilidad de cada incidente
 * (timestamp, probabilidad inferida, ubicación si está disponible).
 */
data class EmergencyEvent(
    val timestampMs: Long,
    val probability: Float,
    val latitude: Double?,
    val longitude: Double?,
    val address: String?,
    val smsSent: Boolean
) {
    val hasLocation: Boolean
        get() = latitude != null && longitude != null

    /** Genera el enlace de Google Maps a partir de las coordenadas. */
    fun googleMapsUrl(): String? {
        if (!hasLocation) return null
        return "https://maps.google.com/?q=$latitude,$longitude"
    }
}