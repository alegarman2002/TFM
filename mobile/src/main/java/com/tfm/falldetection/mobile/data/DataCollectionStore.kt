package com.tfm.falldetection.mobile.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter

/**
 * DataCollectionStore
 *
 * Persiste datos para construir un dataset propio de entrenamiento/validación.
 * Escribe dos CSV en /Android/data/<package>/files/dataset/ (accesible por
 * adb pull o MTP):
 *
 *   - samples.csv : flujo continuo del acelerómetro, una fila por muestra.
 *       session_id, sample_index, timestamp_ms, ax, ay, az
 *   - events.csv  : eventos etiquetados sobre la misma línea temporal.
 *       session_id, timestamp_ms, event_type, label, probability, source
 *
 * GARANTÍA DE ORDEN TEMPORAL: todas las escrituras pasan por un Channel FIFO
 * procesado por un ÚNICO consumidor secuencial; no hay entrelazado. El
 * sample_index es monotónico por sesión (detecta pérdidas de paquetes) y el
 * session_id aísla cada sesión (nunca se concatenan dos como flujo continuo).
 *
 * NOTA: el split train/test temporal NO se hace aquí; la app solo graba en
 * orden. El corte por timestamp/sesión se realiza después en el script de
 * conciliación, respetando el flujo temporal sin barajar.
 */
object DataCollectionStore {

    private const val TAG = "DataCollectionStore"

    private const val SAMPLES_FILE = "samples.csv"
    private const val EVENTS_FILE  = "events.csv"

    private const val SAMPLES_HEADER = "session_id,sample_index,timestamp_ms,ax,ay,az"
    private const val EVENTS_HEADER  = "session_id,timestamp_ms,event_type,label,probability,source"

    private sealed class WriteOp {
        data class Sample(val line: String) : WriteOp()
        data class Event(val line: String)  : WriteOp()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<WriteOp>(capacity = Channel.UNLIMITED)

    private var samplesWriter: FileWriter? = null
    private var eventsWriter:  FileWriter? = null

    @Volatile private var started = false
    @Volatile var currentSessionId: Long = 0L
        private set

    // --- Estado observable para la UI ---
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _sampleCount = MutableStateFlow(0L)
    val sampleCount: StateFlow<Long> = _sampleCount

    private val _eventCount = MutableStateFlow(0L)
    val eventCount: StateFlow<Long> = _eventCount

    fun startSession(context: Context): Long {
        if (started) {
            Log.w(TAG, "startSession ignorado: sesión activa ($currentSessionId)")
            return currentSessionId
        }

        val dir = File(context.getExternalFilesDir(null), "dataset").apply { mkdirs() }
        val samplesFile = File(dir, SAMPLES_FILE)
        val eventsFile  = File(dir, EVENTS_FILE)

        val needSamplesHeader = !samplesFile.exists() || samplesFile.length() == 0L
        val needEventsHeader  = !eventsFile.exists()  || eventsFile.length()  == 0L

        samplesWriter = FileWriter(samplesFile, true).also {
            if (needSamplesHeader) { it.appendLine(SAMPLES_HEADER); it.flush() }
        }
        eventsWriter = FileWriter(eventsFile, true).also {
            if (needEventsHeader) { it.appendLine(EVENTS_HEADER); it.flush() }
        }

        currentSessionId = System.currentTimeMillis()
        _sampleCount.value = 0L
        _eventCount.value = 0L
        started = true
        _active.value = true

        scope.launch {
            for (op in channel) {
                try {
                    when (op) {
                        is WriteOp.Sample -> samplesWriter?.apply { appendLine(op.line); flush() }
                        is WriteOp.Event  -> eventsWriter?.apply  { appendLine(op.line); flush() }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error escribiendo CSV: ${e.message}", e)
                }
            }
        }

        Log.i(TAG, "Sesión iniciada: $currentSessionId (dir=${dir.absolutePath})")
        return currentSessionId
    }

    fun appendSample(sampleIndex: Long, timestampMs: Long, ax: Float, ay: Float, az: Float) {
        if (!started) return
        channel.trySend(WriteOp.Sample("$currentSessionId,$sampleIndex,$timestampMs,$ax,$ay,$az"))
        _sampleCount.value = _sampleCount.value + 1
    }

    fun appendEvent(
        timestampMs: Long,
        eventType: String,
        label: String,
        probability: Float?,
        source: String
    ) {
        if (!started) return
        val probStr = probability?.toString() ?: ""
        channel.trySend(WriteOp.Event("$currentSessionId,$timestampMs,$eventType,$label,$probStr,$source"))
        _eventCount.value = _eventCount.value + 1
    }

    fun endSession() {
        if (!started) return
        started = false
        _active.value = false
        try {
            samplesWriter?.flush(); samplesWriter?.close()
            eventsWriter?.flush();  eventsWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando ficheros: ${e.message}", e)
        }
        samplesWriter = null
        eventsWriter  = null
        Log.i(TAG, "Sesión $currentSessionId finalizada")
    }

    fun isActive(): Boolean = started
}