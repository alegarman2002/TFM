package com.tfm.falldetection.mobile.data

// === RECOGIDA DE DATOS (prueba de la instrumentación; rama recogida-datos) ===

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Prueba de regresión del problema 7.4.1.11 (condición de carrera en el
 * escritor de ficheros del conjunto de datos).
 *
 * Reproduce el escenario que provocó la corrupción original: dos sesiones
 * de recogida encadenadas SIN reiniciar la aplicación. Con el defecto
 * presente, la segunda sesión arrancaba un consumidor adicional y las
 * escrituras se entrelazaban (filas fusionadas + líneas vacías). Con la
 * corrección (consumidor único por proceso), el fichero resultante debe
 * ser íntegro: todas las filas con exactamente 6 campos, sin líneas vacías
 * intermedias y con el número total de filas esperado.
 *
 * Ejecución: gradlew :mobile:connectedDebugAndroidTest
 * (solo en la rama recogida-datos)
 *
 * @author Alejandro García Mansilla
 */
@RunWith(AndroidJUnit4::class)
class DataCollectionStoreInstrumentedTest {

    private val ctx get() =
        ApplicationProvider.getApplicationContext<android.content.Context>()

    private val samplesFile: File
        get() = File(File(ctx.getExternalFilesDir(null), "dataset"), "samples.csv")

    private val eventsFile: File
        get() = File(File(ctx.getExternalFilesDir(null), "dataset"), "events.csv")

    companion object {
        private const val MUESTRAS_POR_SESION = 600
        private const val TIMEOUT_MS = 10_000L
    }

    @Before
    fun partirDeFicherosLimpios() {
        if (DataCollectionStore.isActive()) DataCollectionStore.endSession()
        samplesFile.delete()
        eventsFile.delete()
    }

    /** Espera activa hasta que el fichero alcance el nº de líneas esperado. */
    private fun esperarLineas(fichero: File, esperadas: Int) {
        val limite = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < limite) {
            if (fichero.exists() &&
                fichero.readLines().size >= esperadas) return
            Thread.sleep(50)
        }
    }

    private fun ejecutarSesion(nMuestras: Int, conEvento: Boolean) {
        DataCollectionStore.startSession(ctx)
        val base = System.currentTimeMillis()
        for (i in 0 until nMuestras) {
            DataCollectionStore.appendSample(
                sampleIndex = i.toLong(),
                timestampMs = base + i * 20L,
                ax = 0.1f, ay = 9.8f, az = 0.2f
            )
        }
        if (conEvento) {
            DataCollectionStore.appendEvent(
                timestampMs = base + nMuestras * 20L,
                eventType = "ETIQUETA",
                label = "NO_CAIDA",
                probability = 0.5f,
                source = "cancelacion"
            )
        }
        // Drenar la cola antes de cerrar la sesión (en producción, entre el
        // último dato y la pulsación de DETENER transcurren segundos).
        val lineasPrevias = 1 + contarLineasDatos(samplesFile) // aproximación
        esperarLineas(samplesFile, lineasPrevias)
        DataCollectionStore.endSession()
    }

    private fun contarLineasDatos(f: File): Int =
        if (!f.exists()) 0 else f.readLines().count { it.isNotBlank() } - 1

    @Test
    fun dosSesionesEncadenadas_sinReinicio_produceFicheroIntegro() {
        // Sesión 1
        DataCollectionStore.startSession(ctx)
        val base1 = System.currentTimeMillis()
        for (i in 0 until MUESTRAS_POR_SESION) {
            DataCollectionStore.appendSample(i.toLong(), base1 + i * 20L, 0.1f, 9.8f, 0.2f)
        }
        esperarLineas(samplesFile, 1 + MUESTRAS_POR_SESION)
        DataCollectionStore.endSession()

        // Sesión 2 encadenada (el escenario que corrompía el fichero)
        DataCollectionStore.startSession(ctx)
        val base2 = System.currentTimeMillis()
        for (i in 0 until MUESTRAS_POR_SESION) {
            DataCollectionStore.appendSample(i.toLong(), base2 + i * 20L, 0.3f, 9.7f, 0.1f)
        }
        DataCollectionStore.appendEvent(base2 + 12_000L, "ETIQUETA", "NO_CAIDA", 0.5f, "cancelacion")
        esperarLineas(samplesFile, 1 + 2 * MUESTRAS_POR_SESION)
        esperarLineas(eventsFile, 2)
        DataCollectionStore.endSession()

        // Verificación de integridad
        val lineas = samplesFile.readLines()
        assertEquals(
            "El fichero debe contener cabecera + todas las muestras de ambas sesiones",
            1 + 2 * MUESTRAS_POR_SESION, lineas.size
        )

        lineas.forEachIndexed { n, linea ->
            assertTrue("Línea $n vacía: síntoma de entrelazado", linea.isNotBlank())
            assertEquals(
                "Línea $n con nº de campos incorrecto (fila fusionada): '$linea'",
                6, linea.split(",").size
            )
        }

        // Los índices de muestra deben ser numéricos y las sesiones distintas
        val sesiones = lineas.drop(1).map { it.split(",")[0] }.toSet()
        assertEquals("Deben registrarse exactamente dos session_id distintos",
            2, sesiones.size)

        val eventos = eventsFile.readLines()
        assertEquals("events.csv debe contener cabecera + 1 evento", 2, eventos.size)
        assertEquals("El evento debe tener 6 campos", 6, eventos[1].split(",").size)
    }
}
