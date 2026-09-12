package com.tfm.falldetection.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de la validación del contacto de emergencia.
 *
 * Corresponden a los casos del plan de pruebas 5.8.1:
 *   - "guardarContacto('TextoAleatorio') → false"
 *   - "guardarContacto('+34600123456') → true"
 *
 * Nota de trazabilidad: en el diseño final, la responsabilidad de la
 * validación no reside en EmergencyManager (como preveía el análisis) sino
 * en la capa de datos: la propiedad EmergencySettings.isConfigured, que la
 * interfaz consulta antes de permitir la persistencia. Estas pruebas
 * verifican exactamente esa lógica de aceptación/rechazo.
 *
 * Se ejecutan como pruebas locales de JVM (sin dispositivo):
 *   gradlew :mobile:testDebugUnitTest
 *
 * @author Alejandro García Mansilla
 */
class EmergencySettingsTest {

    @Test
    fun contactoValido_esAceptado() {
        val s = EmergencySettings(
            contactName = "María",
            contactPhone = "+34600123456",
            countdownSecs = 10
        )
        assertTrue("Un teléfono válido debe aceptarse", s.isConfigured)
    }

    @Test
    fun textoAleatorio_esRechazado() {
        val s = EmergencySettings(
            contactName = "María",
            contactPhone = "TextoAleatorio",
            countdownSecs = 10
        )
        assertFalse("Un texto no numérico debe rechazarse", s.isConfigured)
    }

    @Test
    fun telefonoDemasiadoCorto_esRechazado() {
        val s = EmergencySettings(
            contactName = "María",
            contactPhone = "60012345",   // 8 dígitos
            countdownSecs = 10
        )
        assertFalse("Menos de 9 dígitos debe rechazarse", s.isConfigured)
    }

    @Test
    fun nombreVacio_esRechazado() {
        val s = EmergencySettings(
            contactName = "   ",
            contactPhone = "+34600123456",
            countdownSecs = 10
        )
        assertFalse("Sin nombre de contacto no hay configuración válida", s.isConfigured)
    }
}
