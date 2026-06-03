package com.tfm.falldetection.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * SensorCaptureService
 *
 * Foreground Service que captura el acelerómetro a 50 Hz y aplica un
 * pre-filtro de pico de aceleración antes de enviar tramas al smartphone.
 *
 * Diseño:
 *
 *   - Buffer circular en memoria de 150 muestras (3 s), donde cada muestra
 *     contiene los ejes (ax, ay, az) en m/s².
 *
 *   - En cada lectura, calcula la magnitud |a| = √(ax² + ay² + az²).
 *
 *   - Si |a| supera el umbral PEAK_THRESHOLD (≈ 2 g) y no estamos en
 *     periodo de cooldown, se inicia una captura: espera POST_PEAK_SAMPLES
 *     muestras adicionales (1.5 s después del pico) para que el evento
 *     quede centrado en la ventana, y entonces envía las 150 muestras al
 *     móvil vía Wearable MessageClient con el path "/sensor_data".
 *
 *   - Tras el envío activa un cooldown de COOLDOWN_MS milisegundos durante
 *     el cual no se disparan nuevas detecciones, evitando enviar varias
 *     veces la misma caída.
 *
 * El servicio es de tipo dataSync y muestra una notificación persistente
 * mientras está activo. El usuario lo arranca y detiene desde el botón
 * ACTIVAR / DETENER de MainActivity.
 */
class SensorCaptureService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "SensorCaptureService"

        private const val NOTIF_CHANNEL_ID = "wear_monitoring"
        private const val NOTIF_ID         = 2001

        private const val SAMPLE_RATE_HZ      = 50
        private const val WINDOW_SIZE         = 150         // 3 s a 50 Hz
        private const val POST_PEAK_SAMPLES   = 75          // 1.5 s tras el pico
        private const val PEAK_THRESHOLD_MS2  = 20f         // ≈ 2 g
        private const val COOLDOWN_MS         = 5_000L      // 5 s tras envío

        private const val MESSAGE_PATH = "/sensor_data"

        private const val ACTION_START = "com.tfm.falldetection.wear.START"
        private const val ACTION_STOP  = "com.tfm.falldetection.wear.STOP"

        fun start(context: Context) {
            val intent = Intent(context, SensorCaptureService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SensorCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // --- Estado interno del buffer ---
    private val bufferX = FloatArray(WINDOW_SIZE)
    private val bufferY = FloatArray(WINDOW_SIZE)
    private val bufferZ = FloatArray(WINDOW_SIZE)
    private var bufferHead = 0           // posición de escritura
    private var bufferFilled = 0         // muestras válidas acumuladas

    // --- Estado del detector ---
    private var captureCountdown = -1    // muestras restantes tras pico (-1 = ocioso)
    private var lastCaptureMs    = 0L    // timestamp del último envío

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sendJob: Job? = null

    // ─────────────────────────── Service ───────────────────────────

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        Log.i(TAG, "Service onCreate (acelerómetro disponible=${accelerometer != null})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP recibido")
                stopMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.i(TAG, "ACTION_START / arranque")
                startInForeground()
                startMonitoring()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service onDestroy")
        stopMonitoring()
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────── Notificación ──────────────────────────

    private fun startInForeground() {
        ensureChannel()

        val notif: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Detector activo")
            .setContentText("Monitorizando posibles caídas")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(this, NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Monitorización en reloj",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    // ─────────────────────────── Captura ──────────────────────────────

    private fun startMonitoring() {
        val acc = accelerometer
        if (acc == null) {
            Log.e(TAG, "El dispositivo no dispone de acelerómetro")
            stopSelf()
            return
        }

        // Periodo en microsegundos para alcanzar 50 Hz
        val samplingPeriodUs = (1_000_000 / SAMPLE_RATE_HZ)
        val ok = sensorManager.registerListener(this, acc, samplingPeriodUs)
        Log.i(TAG, "registerListener ok=$ok @ ${SAMPLE_RATE_HZ}Hz")

        // Reset del estado interno
        bufferHead       = 0
        bufferFilled     = 0
        captureCountdown = -1
        lastCaptureMs    = 0L
    }

    private fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        Log.i(TAG, "Sensor desregistrado")
    }

    // ─────────────────────────── SensorEventListener ──────────────────

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No relevante para este caso
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        // 1. Almacenar en el buffer circular
        bufferX[bufferHead] = ax
        bufferY[bufferHead] = ay
        bufferZ[bufferHead] = az
        bufferHead = (bufferHead + 1) % WINDOW_SIZE
        if (bufferFilled < WINDOW_SIZE) bufferFilled++

        // 2. Si estamos en cooldown tras un envío reciente, no analizar
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastCaptureMs < COOLDOWN_MS) return

        // 3. Si ya tenemos una captura en marcha, descontamos muestras
        if (captureCountdown > 0) {
            captureCountdown--
            if (captureCountdown == 0) {
                Log.w(TAG, "Captura completada — enviando ventana")
                captureCountdown = -1
                lastCaptureMs = nowMs
                dispatchWindow()
            }
            return
        }

        // 4. Detección de pico: magnitud > umbral
        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        if (magnitude >= PEAK_THRESHOLD_MS2 && bufferFilled >= WINDOW_SIZE) {
            Log.w(TAG, "PICO detectado: |a|=$magnitude m/s² — iniciando captura post")
            captureCountdown = POST_PEAK_SAMPLES
        }
    }

    // ─────────────────────────── Envío ─────────────────────────────────

    /**
     * Linealiza el buffer circular en orden cronológico y lo envía al móvil
     * en una sola trama de 1800 bytes (150 muestras × 3 floats × 4 bytes).
     */
    private fun dispatchWindow() {
        val payload = ByteBuffer.allocate(WINDOW_SIZE * 3 * 4)
            .order(ByteOrder.LITTLE_ENDIAN)

        // El buffer circular avanza con bufferHead apuntando a la PRÓXIMA
        // escritura, así que el primer elemento cronológico está en bufferHead
        for (i in 0 until WINDOW_SIZE) {
            val idx = (bufferHead + i) % WINDOW_SIZE
            payload.putFloat(bufferX[idx])
            payload.putFloat(bufferY[idx])
            payload.putFloat(bufferZ[idx])
        }

        val bytes = payload.array()
        Log.i(TAG, "Trama lista: ${bytes.size} bytes")

        sendJob = scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(this@SensorCaptureService)
                val messageClient = Wearable.getMessageClient(this@SensorCaptureService)
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.w(TAG, "Sin nodos conectados — payload descartado")
                    return@launch
                }
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, MESSAGE_PATH, bytes).await()
                    Log.i(TAG, "Trama enviada a ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando trama: ${e.message}", e)
            }
        }
    }
}