package com.tfm.falldetection.mobile

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.tfm.falldetection.mobile.emergency.NotificationHelper

/**
 * MonitoringService
 *
 * Foreground Service que mantiene vivo el proceso de la aplicación para
 * garantizar la recepción continua de tramas del smartwatch incluso
 * cuando todas las Activities están destruidas.
 *
 * Sin este servicio, Android puede destruir el WearableListenerService al
 * cerrar la aplicación desde el listado de recientes o tras periodos
 * prolongados en Doze mode, perdiéndose la capacidad de detección.
 *
 * La notificación persistente asociada al servicio cumple además con la
 * política de Foreground Services exigida desde Android 8 (API 26).
 *
 * Se utiliza el tipo FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE: el servicio
 * atiende al reloj y este tipo no se detiene a las 6 h (Android 15+).
 */
class MonitoringService : Service() {

    companion object {
        private const val TAG = "MonitoringService"

        /**
         * Helper para arrancar el servicio con la API adecuada según versión
         * de Android. En API 26+ debe usarse startForegroundService().
         */
        fun start(context: Context) {
            val intent = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MonitoringService creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "MonitoringService iniciado (startId=$startId)")

        val notif = NotificationHelper.buildMonitoringNotification(this)

        // API 34+ requiere especificar el tipo de foreground service explícitamente
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIF_MONITORING_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NotificationHelper.NOTIF_MONITORING_ID, notif)
        }

        // START_STICKY: si el sistema mata el servicio por presión de memoria,
        // lo recrea cuando haya recursos disponibles
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "MonitoringService destruido")
        super.onDestroy()
    }
}