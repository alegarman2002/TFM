package com.tfm.falldetection.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tfm.falldetection.mobile.data.EmergencyPreferences
import com.tfm.falldetection.mobile.emergency.AlertFeedback
import com.tfm.falldetection.mobile.emergency.EmergencyExecutor
import com.tfm.falldetection.mobile.emergency.MonitoringController
import com.tfm.falldetection.mobile.emergency.NotificationHelper
import com.tfm.falldetection.mobile.ui.ConfigScreen
import com.tfm.falldetection.mobile.ui.DashboardScreen
import com.tfm.falldetection.mobile.ui.DataCollectionScreen
import com.tfm.falldetection.mobile.ui.PreAlertScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * MainActivity
 *
 * Punto de entrada de la app móvil. Inicializa los singletons del sistema,
 * arranca el MonitoringService y gestiona la navegación entre pantallas:
 *   config | dashboard | prealert | datacollection (recogida de datos)
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        grants.forEach { (perm, granted) ->
            android.util.Log.i("MainActivity", "Permiso $perm = $granted")
        }
    }

    private val requiredPermissions: Array<String> by lazy {
        val base = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base += Manifest.permission.POST_NOTIFICATIONS
        }
        base.toTypedArray()
    }

    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        InferenceEngine.getInstance(this)
        EmergencyExecutor.init(this)
        AlertFeedback.init(this)
        MonitoringController.init(this)
        NotificationHelper.ensureChannels(this)
        permissionLauncher.launch(requiredPermissions)

        MonitoringService.start(this)

        val startDestination = runBlocking {
            val prefs = EmergencyPreferences(this@MainActivity)
            if (prefs.settings.first().isConfigured) "dashboard" else "config"
        }

        val initialRoute = if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_PREALERT, false)) {
            "prealert"
        } else {
            startDestination
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = Color(0xFF1A1A2E)
                ) {
                    val nav = rememberNavController()
                    LaunchedEffect(nav) { navController = nav }
                    FallDetectionApp(nav, initialRoute)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_PREALERT, false)) {
            navController?.navigate("prealert")
        }
    }

    override fun onStart() {
        super.onStart()
        EmergencyManager.getInstance(this).setAppInForeground(true)
    }

    override fun onStop() {
        EmergencyManager.getInstance(this).setAppInForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        AlertFeedback.shutdown()
        super.onDestroy()
    }
}

@Composable
fun FallDetectionApp(navController: NavHostController, startDestination: String) {
    NavHost(
        navController    = navController,
        startDestination = startDestination
    ) {
        composable("config") {
            ConfigScreen(
                onSaved = {
                    navController.navigate("dashboard") {
                        popUpTo("config") { inclusive = true }
                    }
                },
                // === RECOGIDA DE DATOS ===
                onOpenDataCollection = { navController.navigate("datacollection") }
            )
        }

        composable("dashboard") {
            DashboardScreen(
                onOpenConfig = { navController.navigate("config") },
                onPreAlert   = {
                    navController.navigate("prealert") {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("prealert") {
            PreAlertScreen(
                onDismissed = {
                    navController.navigate("dashboard") {
                        popUpTo("dashboard") { inclusive = true }
                    }
                }
            )
        }

        // === RECOGIDA DE DATOS ===
        composable("datacollection") {
            DataCollectionScreen(
                onBack = { navController.popBackStack() }
            )
        }
        // === FIN RECOGIDA DE DATOS ===
    }
}