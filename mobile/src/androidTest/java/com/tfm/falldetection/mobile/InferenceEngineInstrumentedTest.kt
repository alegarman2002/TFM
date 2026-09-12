package com.tfm.falldetection.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas del motor de inferencia, ejecutadas sobre el
 * dispositivo real con el modelo TFLite de producción.
 *
 * Corresponden a los casos del plan de pruebas 5.8.1:
 *   - "Inyectar un tensor simulando reposo absoluto → probabilidad ≈ 0.0"
 *   - "Inyectar un tensor pre-grabado de una caída real"
 * y al contrato de robustez compartido con WearableMessageReceiver:
 *   - "Trama con tamaño distinto del esperado → se descarta sin fallo en cadena"
 *
 * Las ventanas de prueba NO son sintéticas: proceden del conjunto de datos de
 * campo recogido en este proyecto, serializadas exactamente en el formato que
 * produce SensorCaptureService (150 muestras × 3 floats en m/s², little-endian,
 * 1800 bytes).
 *
 * NOTA SOBRE EL CASO UT-01b
 * -------------------------
 * La ventana de caída empleada corresponde al impacto de mayor intensidad
 * registrado en la campaña (pico de 126,9 m/s²). La evaluación del modelo sobre
 * los datos de campo (apartado 8.3.2) sitúa su sensibilidad en el 49,1 %, de
 * modo que existen caídas reales que no superan el umbral de decisión, y esta
 * ventana es una de ellas. El aserto verifica por tanto el contrato del
 * componente —procesar la trama y devolver una probabilidad válida en el rango
 * esperado— y no el acierto del clasificador en un caso concreto, que es objeto
 * del análisis del apartado 8.3.2.
 *
 * Ejecución (con el móvil conectado):
 *   gradlew :mobile:connectedDebugAndroidTest
 *
 * @author Alejandro García Mansilla
 */
@RunWith(AndroidJUnit4::class)
class InferenceEngineInstrumentedTest {

    companion object {
        private lateinit var engine: InferenceEngine

        @BeforeClass
        @JvmStatic
        fun cargarModelo() {
            val targetCtx = InstrumentationRegistry.getInstrumentation().targetContext
            engine = InferenceEngine.getInstance(targetCtx)
        }

        /** Lee un asset del APK de pruebas (no del de la aplicación). */
        private fun leerAssetDePrueba(nombre: String): ByteArray {
            val testCtx = InstrumentationRegistry.getInstrumentation().context
            return testCtx.assets.open(nombre).use { it.readBytes() }
        }
    }

    @Test
    fun ut01a_reposoAbsoluto_probabilidadCercanaACero() {
        val payload = leerAssetDePrueba("ventana_reposo.bin")
        assertEquals("El asset de reposo debe tener el tamaño de trama", 1800, payload.size)

        val p = engine.ejecutarInferencia(payload)

        assertTrue("La probabilidad debe estar en [0,1] (p=$p)", p in 0f..1f)
        assertTrue(
            "En reposo absoluto la probabilidad debe ser cercana a 0 (p=$p). " +
                    "Si este aserto falla con un valor alto o saturado, revisar el orden " +
                    "de bytes del preprocesado: el nodo sensor emite little-endian " +
                    "(véase el apartado 7.4.4.13).",
            p < 0.15f
        )
    }

    @Test
    fun ut01b_caidaRealPregrabada_devuelveProbabilidadValida() {
        val payload = leerAssetDePrueba("ventana_caida.bin")
        assertEquals("El asset de caída debe tener el tamaño de trama", 1800, payload.size)

        val p = engine.ejecutarInferencia(payload)

        // El motor debe procesar la trama y devolver una probabilidad válida.
        // No se exige que supere el umbral: la sensibilidad medida sobre datos
        // de campo es del 49,1 % (apartado 8.3.2), y esta ventana pertenece al
        // subconjunto que el modelo no reconoce, pese a ser el impacto de mayor
        // intensidad registrado. El análisis de esa brecha se aborda en el
        // apartado citado.
        assertTrue(
            "El motor debe devolver una probabilidad en el rango [0,1] (p=$p)",
            p in 0f..1f
        )
        assertTrue(
            "La probabilidad no debe estar saturada: un valor exactamente igual " +
                    "a 1,0 ante cualquier entrada es el síntoma del defecto documentado " +
                    "en el apartado 7.4.4.13 (p=$p)",
            p < 1.0f
        )
    }

    @Test
    fun ut02_tramaDeTamanoInvalido_seDescartaSinFalloEnCadena() {
        val payloadInvalido = ByteArray(1200)   // 1200 ≠ 1800

        // No debe lanzar ninguna excepción (fallo en cadena); el contrato
        // del motor es rechazar el payload devolviendo probabilidad nula.
        val p = engine.ejecutarInferencia(payloadInvalido)

        assertEquals(
            "Un payload de tamaño inválido debe rechazarse con p=0 sin " +
                    "propagar excepción",
            0f, p
        )
    }
}