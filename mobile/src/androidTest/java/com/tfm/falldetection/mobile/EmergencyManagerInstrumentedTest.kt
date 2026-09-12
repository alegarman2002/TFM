package com.tfm.falldetection.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tfm.falldetection.mobile.emergency.AlertFeedback
import com.tfm.falldetection.mobile.emergency.NotificationHelper
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de la máquina de estados del gestor de emergencias
 * (prueba adicional a las previstas en 5.8.1; verifica las transiciones
 * especificadas en el diagrama de estados del apartado 6.3.2).
 *
 * Transiciones verificadas:
 *   - PROTEGIDO → PRE_ALERTA ante una detección
 *   - Detección duplicada durante PRE_ALERTA → ignorada
 *   - PRE_ALERTA → PROTEGIDO ante la cancelación
 *
 * Notas de diseño de la prueba:
 *   - Se marca la aplicación como "en primer plano" para que la detección
 *     no dispare la notificación de pantalla completa durante el test.
 *   - No se asigna onSendAlert, de modo que aunque el plazo expirase no se
 *     enviaría ningún SMS.
 *   - La cancelación se invoca inmediatamente, por lo que el countdown no
 *     llega a expirar.
 *
 * Ejecución: gradlew :mobile:connectedDebugAndroidTest
 *
 * @author Alejandro García Mansilla
 */
@RunWith(AndroidJUnit4::class)
class EmergencyManagerInstrumentedTest {

    private lateinit var manager: EmergencyManager

    @Before
    fun preparar() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        NotificationHelper.ensureChannels(ctx)
        AlertFeedback.init(ctx)
        manager = EmergencyManager.getInstance(ctx)
        manager.setAppInForeground(true)
        manager.reset()
    }

    @After
    fun limpiar() {
        manager.reset()
    }

    @Test
    fun deteccion_transicionaAPreAlerta_yCancelacionVuelveAProtegido() {
        Assert.assertEquals(EmergencyManager.Estado.PROTEGIDO, manager.estado.value)

        // PROTEGIDO → PRE_ALERTA
        manager.onCaidaDetectada(0.91f)
        Assert.assertEquals(EmergencyManager.Estado.PRE_ALERTA, manager.estado.value)
        Assert.assertEquals(0.91f, manager.probabilidad.value)

        // Detección duplicada en PRE_ALERTA → ignorada (no cambia nada)
        manager.onCaidaDetectada(0.99f)
        Assert.assertEquals(EmergencyManager.Estado.PRE_ALERTA, manager.estado.value)
        Assert.assertEquals(
            "La detección duplicada no debe sobrescribir la probabilidad",
            0.91f, manager.probabilidad.value
        )

        // PRE_ALERTA → PROTEGIDO por cancelación
        manager.cancelar()
        Assert.assertEquals(EmergencyManager.Estado.PROTEGIDO, manager.estado.value)
        Assert.assertEquals(0, manager.secondsLeft.value)
        Assert.assertEquals(0f, manager.probabilidad.value)
    }

    @Test
    fun cancelarEnProtegido_esInofensivo() {
        Assert.assertEquals(EmergencyManager.Estado.PROTEGIDO, manager.estado.value)
        manager.cancelar()   // no debe lanzar ni cambiar el estado
        Assert.assertEquals(EmergencyManager.Estado.PROTEGIDO, manager.estado.value)
    }
}