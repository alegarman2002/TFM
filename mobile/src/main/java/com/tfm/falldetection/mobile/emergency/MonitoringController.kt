package com.tfm.falldetection.mobile.emergency

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MonitoringController
 *
 * Mantiene el estado global de protección del sistema (protegido vs.
 * pausado) y lo persiste en SharedPreferences para que sobreviva a
 * reinicios de la app.
 *
 * Cuando el usuario pausa la monitorización desde el móvil:
 *   - Se actualiza isProtected a false (la UI cambia el escudo a gris).
 *   - WearableMessageReceiver ignora cualquier trama del reloj.
 *   - Se envía una orden PAUSE al reloj (WearCommandSender) para que
 *     detenga la captura del acelerómetro y ahorre batería.
 *
 * Al reanudar ocurre lo inverso (orden RESUME).
 *
 * Cuando el usuario pausa o reanuda desde el reloj, el reloj envía su
 * estado por "/monitoring_state" y se aplica con setFromWatch(), que no
 * reenvía ninguna orden al reloj para no entrar en bucle.
 */
object MonitoringController {

    private const val TAG          = "MonitoringController"
    private const val PREFS        = "monitoring_prefs"
    private const val KEY_PROTECTED = "is_protected"

    private val _isProtected = MutableStateFlow(true)
    val isProtected: StateFlow<Boolean> = _isProtected

    /** Carga el último estado persistido. Llamar al arrancar la app. */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _isProtected.value = prefs.getBoolean(KEY_PROTECTED, true)
        Log.i(TAG, "init — isProtected=${_isProtected.value}")
    }

    /** Alterna entre protegido y pausado. */
    fun toggle(context: Context) {
        setProtected(context, !_isProtected.value)
    }

    fun setProtected(context: Context, protected: Boolean) {
        guardar(context, protected)
        Log.i(TAG, "setProtected=$protected")

        if (protected) {
            WearCommandSender.sendResume(context)
        } else {
            WearCommandSender.sendPause(context)
        }
    }

    /** Aplica el estado elegido desde el reloj, sin reenviárselo. */
    fun setFromWatch(context: Context, protected: Boolean) {
        guardar(context, protected)
        Log.i(TAG, "setFromWatch=$protected")
    }

    private fun guardar(context: Context, protected: Boolean) {
        _isProtected.value = protected
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROTECTED, protected)
            .apply()
    }
}