package com.tfm.falldetection.mobile

import android.content.Context
import android.util.Log
import com.tfm.falldetection.mobile.data.EmergencyPreferences
import com.tfm.falldetection.mobile.emergency.AlertFeedback
import com.tfm.falldetection.mobile.emergency.NotificationHelper
import com.tfm.falldetection.mobile.emergency.WearStateSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * EmergencyManager
 *
 * Coordina la máquina de estados del sistema de emergencia y mantiene
 * sincronizada la UI del reloj mediante WearStateSender:
 *
 *   PROTEGIDO ──(caída)──► PRE_ALERTA ──(timeout 0s)──► RESOLUCION_SOS
 *                              │                              │
 *                              └──────(cancelar)──────────────┘
 *                                            │
 *                                            ▼
 *                                       PROTEGIDO
 *
 * Cuando hay un cambio de estado se envía un mensaje al reloj:
 *   - START:<secs> al pasar a PRE_ALERTA
 *   - RESOLVED al pasar a RESOLUCION_SOS
 *   - END al volver a PROTEGIDO (cancelación o reset)
 *
 * La cancelación remota desde el reloj llega vía WearableMessageReceiver,
 * que invoca cancelar() en este manager.
 */
class EmergencyManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "EmergencyManager"

        @Volatile private var instance: EmergencyManager? = null

        fun getInstance(context: Context): EmergencyManager {
            return instance ?: synchronized(this) {
                instance ?: EmergencyManager(context.applicationContext).also { instance = it }
            }
        }
    }

    enum class Estado { PROTEGIDO, PRE_ALERTA, RESOLUCION_SOS }

    private val _estado = MutableStateFlow(Estado.PROTEGIDO)
    val estado: StateFlow<Estado> = _estado

    private val _probabilidad = MutableStateFlow(0f)
    val probabilidad: StateFlow<Float> = _probabilidad

    private val _secondsLeft = MutableStateFlow(0)
    val secondsLeft: StateFlow<Int> = _secondsLeft

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = EmergencyPreferences(context)
    private var countdownJob: Job? = null

    @Volatile private var appInForeground: Boolean = false

    fun setAppInForeground(value: Boolean) {
        appInForeground = value
    }

    var onSendAlert: (suspend (Float) -> Unit)? = null

    fun onCaidaDetectada(probability: Float) {
        if (_estado.value != Estado.PROTEGIDO) {
            Log.d(TAG, "Caída detectada pero estado=${_estado.value}, ignorando")
            return
        }

        Log.w(TAG, "Transición → PRE_ALERTA (p=$probability)")
        _probabilidad.value = probability
        _estado.value = Estado.PRE_ALERTA

        if (!appInForeground) {
            Log.i(TAG, "App en background — disparando Full-Screen Intent")
            NotificationHelper.firePreAlertFullScreen(context)
        }

        startCountdown()
    }

    fun cancelar() {
        if (_estado.value != Estado.PRE_ALERTA) {
            Log.d(TAG, "Cancelar invocado pero estado=${_estado.value}")
            return
        }

        Log.i(TAG, "Pre-alerta cancelada")
        countdownJob?.cancel()
        countdownJob = null
        _secondsLeft.value = 0
        _probabilidad.value = 0f
        _estado.value = Estado.PROTEGIDO

        NotificationHelper.clearEmergencyNotification(context)
        WearStateSender.sendEnd(context)
    }

    fun reset() {
        Log.i(TAG, "Reset solicitado (estado actual=${_estado.value})")
        countdownJob?.cancel()
        countdownJob = null
        _secondsLeft.value = 0
        _probabilidad.value = 0f
        _estado.value = Estado.PROTEGIDO

        NotificationHelper.clearEmergencyNotification(context)
        WearStateSender.sendEnd(context)
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            val totalSecs = prefs.settings.first().countdownSecs
            Log.i(TAG, "Iniciando countdown de ${totalSecs}s")

            // Notificamos al reloj para que arranque su pantalla de pre-alerta
            WearStateSender.sendStart(context, totalSecs)

            AlertFeedback.onPreAlertStarted(context, totalSecs)

            for (s in totalSecs downTo 1) {
                _secondsLeft.value = s
                AlertFeedback.onCountdownTick(context, s)
                delay(1_000L)
            }
            _secondsLeft.value = 0

            if (_estado.value == Estado.PRE_ALERTA) {
                Log.w(TAG, "Countdown expirado → RESOLUCION_SOS")
                _estado.value = Estado.RESOLUCION_SOS
                AlertFeedback.onCountdownExpired(context)
                WearStateSender.sendResolved(context)

                try {
                    onSendAlert?.invoke(_probabilidad.value)
                        ?: Log.e(TAG, "onSendAlert no asignado todavía")
                } catch (e: Exception) {
                    Log.e(TAG, "Error al ejecutar onSendAlert: ${e.message}", e)
                }
            }
        }
    }
}