package com.tfm.falldetection.mobile.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de la persistencia de la configuración de
 * emergencia (DataStore), sobre el dispositivo real.
 *
 * Completan el caso del plan 5.8.1 "guardarContacto('+34600123456') →
 * el número queda persistido": tras guardar un contacto válido, la lectura
 * del flujo debe devolver exactamente los valores almacenados y la
 * configuración debe reconocerse como válida.
 *
 * Ejecución: gradlew :mobile:connectedDebugAndroidTest
 *
 * @author Alejandro García Mansilla
 */
@RunWith(AndroidJUnit4::class)
class EmergencyPreferencesInstrumentedTest {

    @Test
    fun guardarContactoValido_quedaPersistidoYConfigurado() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = EmergencyPreferences(ctx)

        prefs.save(name = "María Test", phone = "+34600123456", countdownSecs = 12)

        val s = prefs.settings.first()
        assertEquals("María Test", s.contactName)
        assertEquals("+34600123456", s.contactPhone)
        assertEquals(12, s.countdownSecs)
        assertTrue("Tras guardar un contacto válido, isConfigured debe ser true",
            s.isConfigured)
    }

    @Test
    fun countdownFueraDeRango_seAcotaAlIntervaloPermitido() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = EmergencyPreferences(ctx)

        prefs.save(name = "María Test", phone = "+34600123456", countdownSecs = 99)
        assertEquals("El plazo debe acotarse al máximo permitido (30 s)",
            30, prefs.settings.first().countdownSecs)

        prefs.save(name = "María Test", phone = "+34600123456", countdownSecs = 1)
        assertEquals("El plazo debe acotarse al mínimo permitido (5 s)",
            5, prefs.settings.first().countdownSecs)
    }
}
