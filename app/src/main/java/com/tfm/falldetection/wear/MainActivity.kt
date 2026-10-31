package com.tfm.falldetection.wear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * MainActivity (Wear OS) — Galaxy Watch 7 40mm
 *
 * Gestiona tres modos de visualización gobernados por WearAlertState:
 *
 *   - NORMAL    : pantalla principal con botón ACTIVAR/DETENER
 *   - PRE_ALERT : pantalla roja con countdown y botón ESTOY BIEN
 *   - RESOLVED  : pantalla morada con spinner mientras el móvil envía SMS
 *
 * showWhenLocked/turnScreenOn permiten que la pre-alerta sea visible
 * incluso con la pantalla apagada.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WearMainActivity"
    }

    private val permissionsGranted = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsGranted.value = hasAllSensorPermissions(this)
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        permissionsGranted.value = hasAllSensorPermissions(this)
    }

    private val criticalPermissions: List<String> by lazy {
        val base = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            base += Manifest.permission.ACTIVITY_RECOGNITION
        }
        base
    }

    private val allRequestable: Array<String> by lazy {
        val base = criticalPermissions.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base += Manifest.permission.POST_NOTIFICATIONS
        }
        base.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsGranted.value = hasAllSensorPermissions(this)
        if (!permissionsGranted.value) tryRequestPermissions()

        setContent {
            MaterialTheme {
                WearApp(
                    permissionsGranted = permissionsGranted.value,
                    onGrantPressed = { tryRequestPermissions() },
                    onOpenSettings = { openAppSettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsGranted.value = hasAllSensorPermissions(this)
    }

    private fun tryRequestPermissions() {
        if (allRequestable.isEmpty()) { permissionsGranted.value = true; return }

        val needsSettings = criticalPermissions.any { perm ->
            val notGranted = ContextCompat.checkSelfPermission(this, perm) !=
                    PackageManager.PERMISSION_GRANTED
            val cannotShow = !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
            notGranted && cannotShow
        }

        val prefs = getSharedPreferences("perm_prefs", MODE_PRIVATE)
        val hasRequested = prefs.getBoolean("requested", false)

        if (needsSettings && hasRequested) {
            openAppSettings()
        } else {
            prefs.edit().putBoolean("requested", true).apply()
            permissionLauncher.launch(allRequestable)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { settingsLauncher.launch(intent) }
        catch (e: Exception) { Log.e(TAG, "No se pudo abrir Ajustes: ${e.message}") }
    }
}

// ─────────────────────────── Composables ───────────────────────────

@Composable
private fun WearApp(
    permissionsGranted: Boolean,
    onGrantPressed: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val mode by WearAlertState.mode.collectAsState()
    val secondsLeft by WearAlertState.secondsLeft.collectAsState()

    // Capa de pre-alerta: tiene prioridad absoluta sobre la pantalla normal
    when (mode) {
        WearAlertState.Mode.PRE_ALERT -> {
            PreAlertView(secondsLeft = secondsLeft)
            return
        }
        WearAlertState.Mode.RESOLVED -> {
            ResolvedView()
            return
        }
        else -> Unit
    }

    NormalView(
        permissionsGranted = permissionsGranted,
        onGrantPressed = onGrantPressed,
        onOpenSettings = onOpenSettings
    )
}

@Composable
private fun NormalView(
    permissionsGranted: Boolean,
    onGrantPressed: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    // Estado real del servicio de captura: cambia también si el teléfono pausa o reanuda
    val monitoring by SensorCaptureService.activo.collectAsState()

    val background = when {
        !permissionsGranted -> Color(0xFF5D4037)
        monitoring          -> Color(0xFF1B5E20)
        else                -> Color(0xFF1A1A2E)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(0.78f).wrapContentHeight()
        ) {
            if (!permissionsGranted) {
                Text("Permisos", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                Text("Actividad física", fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                CompactButton("CONCEDER", Color(0xFF1976D2), onGrantPressed)
                Spacer(Modifier.height(4.dp))
                CompactButton("Ajustes", Color.White.copy(alpha = 0.25f), onOpenSettings, 9.sp)
            } else {
                Text(if (monitoring) "ACTIVO" else "INACTIVO",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                Text(if (monitoring) "Vigilando caídas" else "Pulsa para iniciar",
                    fontSize = 9.sp, color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                CompactButton(
                    text = if (monitoring) "DETENER" else "ACTIVAR",
                    color = if (monitoring) Color(0xFFC62828) else Color(0xFF2E7D32),
                    onClick = {
                        if (monitoring) SensorCaptureService.stop(context)
                        else            SensorCaptureService.start(context)
                        // Avisa al teléfono para que su escudo refleje el mismo estado
                        PhoneStateSender.send(context, !monitoring)
                    }
                )
            }
        }
    }
}

@Composable
private fun PreAlertView(secondsLeft: Int) {
    val context = LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFC62828)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(0.82f).wrapContentHeight()
        ) {
            Text(
                text = "¿ESTÁS BIEN?",
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$secondsLeft",
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    CancelSender.send(context)
                    WearAlertState.endPreAlert()
                },
                colors = ButtonDefaults.primaryButtonColors(
                    backgroundColor = Color.White
                ),
                modifier = Modifier
                    .size(width = 130.dp, height = 42.dp)
                    .clip(CircleShape)
            ) {
                Text(
                    "ESTOY BIEN",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFC62828)
                )
            }
        }
    }
}

@Composable
private fun ResolvedView() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF6A1B9A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                strokeWidth = 3.dp,
                indicatorColor = Color.White,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Avisando…",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun CompactButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.primaryButtonColors(backgroundColor = color),
        modifier = Modifier
            .size(width = 110.dp, height = 34.dp)
            .clip(CircleShape)
    ) {
        Text(text, fontSize = fontSize, fontWeight = FontWeight.Bold,
            color = Color.White, textAlign = TextAlign.Center)
    }
}

private fun hasAllSensorPermissions(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACTIVITY_RECOGNITION
    ) == PackageManager.PERMISSION_GRANTED
}