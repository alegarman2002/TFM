package com.tfm.falldetection.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * WearAlertState
 *
 * Singleton que mantiene el estado de la pre-alerta en el reloj. Es
 * alimentado por los mensajes "/prealert_state" del móvil mediante
 * PhoneMessageReceiver. Mantiene un countdown local sincronizado con el
 * móvil al inicio de la pre-alerta y emite una vibración corta cada
 * segundo, igual que la cuenta atrás del smartphone.
 *
 * Estados posibles:
 *   - NORMAL    : pantalla principal del reloj (INACTIVO o ACTIVO)
 *   - PRE_ALERT : pantalla de pre-alerta con countdown y botón ESTOY BIEN
 *   - RESOLVED  : el móvil ha enviado el aviso al contacto (estado breve)
 */
object WearAlertState {

    private const val TAG = "WearAlertState"

    enum class Mode { NORMAL, PRE_ALERT, RESOLVED }

    private val _mode = MutableStateFlow(Mode.NORMAL)
    val mode: StateFlow<Mode> = _mode

    private val _secondsLeft = MutableStateFlow(0)
    val secondsLeft: StateFlow<Int> = _secondsLeft

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var countdownJob: Job? = null

    fun startPreAlert(context: Context, totalSecs: Int) {
        Log.w(TAG, "PRE_ALERT iniciada — ${totalSecs}s")
        _mode.value = Mode.PRE_ALERT

        val appCtx = context.applicationContext
        countdownJob?.cancel()
        countdownJob = scope.launch {
            // Vibración inicial doble corta para llamar la atención
            vibrate(appCtx, 200)
            delay(150)
            vibrate(appCtx, 200)

            for (s in totalSecs downTo 1) {
                _secondsLeft.value = s
                vibrate(appCtx, 150)
                delay(1_000)
            }
            _secondsLeft.value = 0
            // No hacemos nada más aquí: el móvil enviará "RESOLVED" o "END"
        }
    }

    fun setResolved(context: Context) {
        Log.w(TAG, "Estado: RESOLVED")
        countdownJob?.cancel()
        countdownJob = null
        _secondsLeft.value = 0
        _mode.value = Mode.RESOLVED
        vibrate(context, 800)
    }

    fun endPreAlert() {
        Log.i(TAG, "Estado: NORMAL")
        countdownJob?.cancel()
        countdownJob = null
        _secondsLeft.value = 0
        _mode.value = Mode.NORMAL
    }

    // ------ Helpers de vibración ------

    private fun vibrate(context: Context, durationMs: Long) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error vibrando: ${e.message}")
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}