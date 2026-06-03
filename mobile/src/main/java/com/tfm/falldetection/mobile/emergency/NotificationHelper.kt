package com.tfm.falldetection.mobile.emergency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tfm.falldetection.mobile.MainActivity

/**
 * NotificationHelper
 *
 * Centraliza la creación de canales y notificaciones del sistema.
 *
 * Define dos canales:
 *
 *   - CHANNEL_MONITORING: prioridad LOW, asociada al Foreground Service
 *     que mantiene vivo el proceso de monitorización. Visible mientras la
 *     app está protegiendo al usuario, no produce sonido ni vibración.
 *
 *   - CHANNEL_EMERGENCY: prioridad HIGH con bypass de "No molestar". Se usa
 *     para disparar Full-Screen Intents que abren la PreAlertScreen cuando
 *     se detecta una caída con la app en segundo plano o el dispositivo
 *     bloqueado.
 */
object NotificationHelper {

    const val CHANNEL_MONITORING = "monitoring"
    const val CHANNEL_EMERGENCY  = "emergency"

    const val NOTIF_MONITORING_ID = 1001
    const val NOTIF_EMERGENCY_ID  = 1002

    const val EXTRA_OPEN_PREALERT = "open_prealert"

    /**
     * Crea los canales de notificación si no existen. Idempotente.
     * En API < 26 los canales no existen y la función no hace nada.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return

        val chMon = NotificationChannel(
            CHANNEL_MONITORING,
            "Monitorización",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificación persistente del servicio de monitorización"
            setShowBadge(false)
        }
        nm.createNotificationChannel(chMon)

        val chEmerg = NotificationChannel(
            CHANNEL_EMERGENCY,
            "Alertas de emergencia",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notificaciones de pre-alerta tras detección de caída"
            setBypassDnd(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(chEmerg)
    }

    /**
     * Notificación persistente asociada al MonitoringService. Al pulsarla
     * el usuario abre la pantalla principal de la app.
     */
    fun buildMonitoringNotification(context: Context): Notification {
        ensureChannels(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingTap = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Sistema protegido")
            .setContentText("Detector de caídas activo en segundo plano")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingTap)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Dispara la pantalla de pre-alerta como Full-Screen Intent. En Android
     * moderno, si la actividad de destino tiene `showWhenLocked="true"` y
     * `turnScreenOn="true"`, esta notificación abre la PreAlertScreen
     * directamente, encendiendo la pantalla incluso si el dispositivo
     * estaba bloqueado.
     */
    fun firePreAlertFullScreen(context: Context) {
        ensureChannels(context)

        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PREALERT, true)
        }
        val pendingFullScreen = PendingIntent.getActivity(
            context, 1, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_EMERGENCY)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("¿Estás bien?")
            .setContentText("Pulsa para cancelar la alerta")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingFullScreen, true)
            .setContentIntent(pendingFullScreen)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
        nm?.notify(NOTIF_EMERGENCY_ID, notif)
    }

    /**
     * Retira la notificación de emergencia al cancelar o tras enviar el SMS.
     */
    fun clearEmergencyNotification(context: Context) {
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
        nm?.cancel(NOTIF_EMERGENCY_ID)
    }
}