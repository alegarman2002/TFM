package com.tfm.falldetection.mobile

import android.app.Application
import com.tfm.falldetection.mobile.emergency.EmergencyExecutor
import com.tfm.falldetection.mobile.emergency.MonitoringController

/**
 * Se ejecuta al arrancar el proceso, también cuando lo arranca el sistema
 * para entregar un mensaje del reloj con la aplicación cerrada: así el envío
 * del SMS y el estado de vigilancia quedan listos aunque no se abra MainActivity.
 */
class FallDetectionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EmergencyExecutor.init(this)
        MonitoringController.init(this)
    }
}