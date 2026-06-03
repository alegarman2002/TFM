package com.tfm.falldetection.mobile.emergency

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * LocationProvider
 *
 * Obtiene la ubicación actual del dispositivo usando FusedLocationProvider
 * y, opcionalmente, resuelve la dirección textual mediante Geocoder.
 *
 * Es una clase no-instanciable de utilidad: todas las funciones son suspend
 * para integrarse limpiamente con corrutinas.
 *
 * Uso típico:
 *   val info = LocationProvider.getLocationInfo(context)
 *   info?.let { sendSmsWith(it.latitude, it.longitude, it.address) }
 */
object LocationProvider {

    private const val TAG = "LocationProvider"

    /** Datos de localización resueltos. */
    data class LocationInfo(
        val latitude: Double,
        val longitude: Double,
        val address: String?,
        val accuracyMeters: Float
    )

    /**
     * Verifica si la app tiene concedido alguno de los permisos de ubicación
     * (FINE o COARSE). Sin esto, no podemos llamar a FusedLocationProvider.
     */
    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    /**
     * Solicita la ubicación actual con prioridad alta. Si tarda más de 8s,
     * devuelve null para no bloquear el envío del SMS indefinidamente.
     *
     * @return LocationInfo con coordenadas y dirección textual, o null si falla.
     */
    @Suppress("MissingPermission")  // se valida arriba con hasLocationPermission()
    suspend fun getLocationInfo(context: Context): LocationInfo? {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "Sin permiso de ubicación — devolviendo null")
            return null
        }

        val location = withTimeoutOrNull(8_000L) { fetchCurrentLocation(context) }
        if (location == null) {
            Log.w(TAG, "Timeout o fallo al obtener la ubicación")
            return null
        }

        Log.i(TAG, "Ubicación obtenida: (${location.latitude}, ${location.longitude})")
        val address = resolveAddress(context, location.latitude, location.longitude)

        return LocationInfo(
            latitude       = location.latitude,
            longitude      = location.longitude,
            address        = address,
            accuracyMeters = location.accuracy
        )
    }

    /**
     * Llamada suspendable a FusedLocationProvider. Se usa la corrutina-cancelable
     * para que un cancel externo aborte limpiamente la espera.
     */
    @Suppress("MissingPermission")
    private suspend fun fetchCurrentLocation(context: Context): Location? =
        suspendCancellableCoroutine { cont ->
            val client = LocationServices.getFusedLocationProviderClient(context)

            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
                .addOnFailureListener { ex ->
                    Log.e(TAG, "FusedLocationProvider falló: ${ex.message}")
                    if (cont.isActive) cont.resume(null)
                }
        }

    /**
     * Geocoder inverso: dado un par (lat, lon), intenta obtener la dirección
     * textual. En API 33+ se usa la versión asíncrona; en versiones previas
     * la síncrona (deprecated pero válida).
     */
    private suspend fun resolveAddress(
        context: Context,
        lat: Double,
        lon: Double
    ): String? = try {
        val geocoder = Geocoder(context, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(3_000L) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1) { addresses ->
                        val text = addresses.firstOrNull()?.getAddressLine(0)
                        if (cont.isActive) cont.resume(text)
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Geocoder inverso falló: ${e.message}")
        null
    }
}