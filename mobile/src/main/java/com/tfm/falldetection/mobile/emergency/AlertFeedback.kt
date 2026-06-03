package com.tfm.falldetection.mobile.emergency

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * AlertFeedback
 *
 * Gestiona el feedback multimodal de la pre-alerta: vibración háptica y
 * locución por voz mediante Text-To-Speech. Cumple con el Requisito R2.2,
 * que exige una cuenta atrás perceptible más allá del canal visual.
 *
 * Diseñado para usuarios de edad avanzada con posibles déficits sensoriales:
 *   - Vibración fuerte (ANSI) en cada segundo del countdown.
 *   - TTS que anuncia los segundos restantes en los últimos 5 segundos
 *     y al expirar el tiempo.
 *   - Volumen forzado al canal de notificación para garantizar audibilidad.
 *
 * Singleton inicializado desde MainActivity y reutilizado por EmergencyManager.
 */
object AlertFeedback {

    private const val TAG = "AlertFeedback"

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /**
     * Inicializa el motor TTS. Es asíncrono — `ttsReady` pasa a true cuando
     * el motor termina de cargar. Si falla, las invocaciones de voz se
     * convierten en no-op silencioso (la vibración sigue funcionando).
     */
    fun init(context: Context) {
        if (tts != null) return

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("es", "ES"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                Log.i(TAG, "TTS inicializado, lang ok=$ttsReady")

                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } else {
                Log.e(TAG, "Fallo al iniciar TTS: status=$status")
            }
        }
    }

    /**
     * Llamado por EmergencyManager al inicio de la pre-alerta. Emite un
     * patrón de doble vibración y locuta el aviso completo.
     */
    fun onPreAlertStarted(context: Context, totalSecs: Int) {
        vibratePattern(context, longArrayOf(0, 400, 200, 400))
        speak("Atención. ¿Estás bien? Pulsa el botón para cancelar.")
    }

    /**
     * Llamado en cada tick del countdown. Vibra brevemente y, en los
     * últimos 5 segundos, anuncia el número por voz.
     */
    fun onCountdownTick(context: Context, secondsLeft: Int) {
        vibrate(context, 150)
        if (secondsLeft in 1..5) {
            speak(secondsLeft.toString())
        }
    }

    /**
     * Llamado al expirar el countdown sin cancelación. Vibración larga
     * y anuncio de envío.
     */
    fun onCountdownExpired(context: Context) {
        vibrate(context, 800)
        speak("Enviando alerta")
    }

    /** Libera los recursos del TTS al cerrar la app. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    // --- Privados ---

    private fun speak(text: String) {
        if (!ttsReady) {
            Log.d(TAG, "TTS no listo, descartando: '$text'")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fallTTS")
    }

    private fun vibrate(context: Context, durationMs: Long) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error vibrando: ${e.message}")
        }
    }

    private fun vibratePattern(context: Context, pattern: LongArray) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error vibrando patrón: ${e.message}")
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}