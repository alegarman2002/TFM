package com.tfm.falldetection.mobile.emergency

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Pruebas unitarias adicionales (no previstas explícitamente en 5.8.1 pero
 * complementarias): composición del cuerpo del mensaje de socorro.
 *
 * Verifican que el mensaje contiene todos los elementos críticos (saludo,
 * alerta, coordenadas, dirección y enlace a Google Maps) y que el caso sin
 * ubicación degrada de forma segura.
 *
 * Se fija Locale.US durante la prueba para que el formato decimal de las
 * coordenadas sea determinista con independencia de la máquina de CI.
 * (Véase la recomendación asociada en la revisión de coherencia: fijar
 * Locale.US también en producción en la llamada a format.)
 *
 * Ejecución: gradlew :mobile:testDebugUnitTest
 *
 * @author Alejandro García Mansilla
 */
class SmsSenderBodyTest {

    private lateinit var localeOriginal: Locale

    @Before
    fun fijarLocale() {
        localeOriginal = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restaurarLocale() {
        Locale.setDefault(localeOriginal)
    }

    @Test
    fun cuerpoConUbicacion_contieneTodosLosElementos() {
        val body = SmsSender.buildMessageBody(
            contactName = "María",
            latitude = 43.360914,
            longitude = -5.845592,
            address = "Calle Uría 1, Oviedo"
        )

        assertTrue("Debe saludar al contacto", body.contains("Hola María"))
        assertTrue("Debe contener la alerta", body.contains("ALERTA AUTOMÁTICA"))
        assertTrue("Debe contener las coordenadas",
            body.contains("43.360914") && body.contains("-5.845592"))
        assertTrue("Debe contener la dirección", body.contains("Calle Uría"))
        assertTrue("Debe contener el enlace a Maps",
            body.contains("https://maps.google.com/?q=43.360914,-5.845592"))
    }

    @Test
    fun cuerpoConUbicacion_superaUnSms_yJustificaMultiparte() {
        val body = SmsSender.buildMessageBody(
            contactName = "María",
            latitude = 43.360914,
            longitude = -5.845592,
            address = "Calle Uría 1, Oviedo"
        )
        assertTrue(
            "Con dirección y enlace, el cuerpo supera los 160 caracteres " +
            "de un SMS simple (longitud=${body.length}), lo que exige el " +
            "envío multiparte verificado en las pruebas de integración",
            body.length > 160
        )
    }

    @Test
    fun sinUbicacion_degradaDeFormaSegura() {
        val body = SmsSender.buildMessageBody(
            contactName = "María",
            latitude = null,
            longitude = null,
            address = null
        )
        assertTrue("Debe avisar de que no hay ubicación",
            body.contains("no disponible"))
        assertFalse("No debe incluir un enlace a Maps sin coordenadas",
            body.contains("maps.google.com"))
    }
}
