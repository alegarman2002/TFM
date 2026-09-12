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
 * Captura el acelerómetro a 50 Hz. Cumple dos funciones simultáneas:
 *
 *  1) MONITORIZACIÓN (producto final): mantiene un buffer circular de 150
 *     muestras y, al detectar un pico (|a| ≥ 2 g), envía la ventana al móvil
 *     por "/sensor_data" para la inferencia.
 *
 *  2) RECOGIDA DE DATOS (=== RECOGIDA DE DATOS ===, solo entrenamiento):
 *     cuando collectionMode está activo, además acumula TODAS las muestras en
 *     lotes de 1 s (50 muestras) y las envía por "/data_collection" con un
 *     índice monotónico de sesión. Esto graba el flujo continuo completo,
 *     incluidas las caídas que el modelo no detecta.
 */
class SensorCaptureService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "SensorCaptureService"

        private const val NOTIF_CHANNEL_ID = "wear_monitoring"
        private const val NOTIF_ID         = 2001

        private const val SAMPLE_RATE_HZ      = 50
        private const val WINDOW_SIZE         = 150
        private const val POST_PEAK_SAMPLES   = 75
        private const val PEAK_THRESHOLD_MS2  = 20f
        private const val COOLDOWN_MS         = 5_000L

        private const val MESSAGE_PATH = "/sensor_data"

        private const val ACTION_START = "com.tfm.falldetection.wear.START"
        private const val ACTION_STOP  = "com.tfm.falldetection.wear.STOP"

        // === RECOGIDA DE DATOS ===
        const val ACTION_START_COLLECTION = "com.tfm.falldetection.wear.START_COLLECTION"
        const val ACTION_STOP_COLLECTION  = "com.tfm.falldetection.wear.STOP_COLLECTION"
        private const val COLLECTION_PATH = "/data_collection"
        private const val BATCH_SIZE      = 50          // 1 s a 50 Hz
        // === FIN RECOGIDA DE DATOS ===

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

    // Buffer circular para monitorización
    private val bufferX = FloatArray(WINDOW_SIZE)
    private val bufferY = FloatArray(WINDOW_SIZE)
    private val bufferZ = FloatArray(WINDOW_SIZE)
    private var bufferHead = 0
    private var bufferFilled = 0

    private var captureCountdown = -1
    private var lastCaptureMs    = 0L

    // === RECOGIDA DE DATOS ===
    @Volatile private var collectionMode = false
    private var collectionSampleIndex = 0L
    private val batchTs = LongArray(BATCH_SIZE)
    private val batchAx = FloatArray(BATCH_SIZE)
    private val batchAy = FloatArray(BATCH_SIZE)
    private val batchAz = FloatArray(BATCH_SIZE)
    private var batchCount = 0
    private var batchStartIndex = 0L
    // === FIN RECOGIDA DE DATOS ===

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sendJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        Log.i(TAG, "Service onCreate (acelerómetro=${accelerometer != null})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP")
                stopMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            // === RECOGIDA DE DATOS ===
            ACTION_START_COLLECTION -> {
                Log.i(TAG, "ACTION_START_COLLECTION")
                startInForeground()
                ensureListening()
                collectionSampleIndex = 0L
                batchCount = 0
                collectionMode = true
            }
            ACTION_STOP_COLLECTION -> {
                Log.i(TAG, "ACTION_STOP_COLLECTION")
                collectionMode = false
                flushBatch()
            }
            // === FIN RECOGIDA DE DATOS ===
            else -> {
                Log.i(TAG, "ACTION_START / arranque")
                startInForeground()
                ensureListening()
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
            NOTIF_CHANNEL_ID, "Monitorización en reloj",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(ch)
    }

    private var listening = false

    private fun ensureListening() {
        if (listening) return
        val acc = accelerometer
        if (acc == null) {
            Log.e(TAG, "Sin acelerómetro")
            stopSelf()
            return
        }
        val periodUs = 1_000_000 / SAMPLE_RATE_HZ
        val ok = sensorManager.registerListener(this, acc, periodUs)
        listening = ok
        Log.i(TAG, "registerListener ok=$ok @ ${SAMPLE_RATE_HZ}Hz")

        bufferHead = 0; bufferFilled = 0
        captureCountdown = -1; lastCaptureMs = 0L
    }

    private fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        listening = false
        Log.i(TAG, "Sensor desregistrado")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        // ---- Monitorización (buffer circular + detección de pico) ----
        bufferX[bufferHead] = ax
        bufferY[bufferHead] = ay
        bufferZ[bufferHead] = az
        bufferHead = (bufferHead + 1) % WINDOW_SIZE
        if (bufferFilled < WINDOW_SIZE) bufferFilled++

        val nowMs = System.currentTimeMillis()

        // === RECOGIDA DE DATOS: acumular en lote y enviar ====
        if (collectionMode) {
            if (batchCount == 0) batchStartIndex = collectionSampleIndex
            batchTs[batchCount] = nowMs
            batchAx[batchCount] = ax
            batchAy[batchCount] = ay
            batchAz[batchCount] = az
            batchCount++
            collectionSampleIndex++
            if (batchCount >= BATCH_SIZE) flushBatch()
        }
        // === FIN RECOGIDA DE DATOS ===

        if (nowMs - lastCaptureMs < COOLDOWN_MS) return

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

        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        if (magnitude >= PEAK_THRESHOLD_MS2 && bufferFilled >= WINDOW_SIZE) {
            Log.w(TAG, "PICO detectado: |a|=$magnitude m/s²")
            captureCountdown = POST_PEAK_SAMPLES
        }
    }

    private fun dispatchWindow() {
        val payload = ByteBuffer.allocate(WINDOW_SIZE * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until WINDOW_SIZE) {
            val idx = (bufferHead + i) % WINDOW_SIZE
            payload.putFloat(bufferX[idx])
            payload.putFloat(bufferY[idx])
            payload.putFloat(bufferZ[idx])
        }
        val bytes = payload.array()
        sendJob = scope.launch { sendBytes(MESSAGE_PATH, bytes) }
    }

    // === RECOGIDA DE DATOS ===
    /**
     * Empaqueta el lote actual y lo envía por "/data_collection". El
     * empaquetado se hace de forma síncrona en el hilo del sensor (rápido)
     * para evitar condiciones de carrera sobre los arrays del lote; solo el
     * envío de red va a una corrutina.
     *
     * Formato little-endian:
     *   Long startSampleIndex | Int count |
     *   count × (Long ts | Float ax | Float ay | Float az)
     */
    private fun flushBatch() {
        val n = batchCount
        if (n == 0) return

        val buf = ByteBuffer.allocate(8 + 4 + n * 20).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(batchStartIndex)
        buf.putInt(n)
        for (i in 0 until n) {
            buf.putLong(batchTs[i])
            buf.putFloat(batchAx[i])
            buf.putFloat(batchAy[i])
            buf.putFloat(batchAz[i])
        }
        batchCount = 0

        val bytes = buf.array()
        scope.launch { sendBytes(COLLECTION_PATH, bytes) }
    }
    // === FIN RECOGIDA DE DATOS ===

    private suspend fun sendBytes(path: String, bytes: ByteArray) {
        try {
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            val client = Wearable.getMessageClient(this)
            if (nodes.isEmpty()) {
                Log.w(TAG, "Sin nodos conectados — payload '$path' descartado")
                return
            }
            nodes.forEach { node ->
                client.sendMessage(node.id, path, bytes).await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando '$path': ${e.message}", e)
        }
    }
}