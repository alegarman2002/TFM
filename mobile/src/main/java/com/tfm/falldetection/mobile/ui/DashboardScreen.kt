package com.tfm.falldetection.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tfm.falldetection.mobile.EmergencyManager
import com.tfm.falldetection.mobile.data.EmergencyPreferences
import com.tfm.falldetection.mobile.emergency.MonitoringController

/**
 * DashboardScreen
 *
 * Pantalla principal del nodo móvil. El escudo central es interactivo:
 *   - Verde → sistema protegido (monitorización activa)
 *   - Gris  → monitorización pausada
 *
 * El borde superior del escudo presenta un pico central pronunciado con
 * dos valles laterales. Al tocarlo se alterna el estado mediante
 * MonitoringController, lo que además envía PAUSE/RESUME al reloj.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenConfig: () -> Unit,
    onPreAlert:   () -> Unit
) {
    val context = LocalContext.current
    val emergencyManager = remember { EmergencyManager.getInstance(context) }
    val prefs = remember { EmergencyPreferences(context) }

    val estado       by emergencyManager.estado.collectAsState()
    val probabilidad by emergencyManager.probabilidad.collectAsState()
    val settings     by prefs.settings.collectAsState(initial = null)
    val isProtected  by MonitoringController.isProtected.collectAsState()

    LaunchedEffect(estado) {
        if (estado == EmergencyManager.Estado.PRE_ALERTA) {
            onPreAlert()
        }
    }

    val backgroundColor = if (isProtected) Color(0xFF12395E) else Color(0xFF37474F)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detector de caídas", color = Color.White) },
                actions = {
                    IconButton(onClick = onOpenConfig) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configuración",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = backgroundColor)
            )
        },
        containerColor = backgroundColor
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {

            ShieldIcon(
                fill    = if (isProtected) Color(0xFF43A047) else Color(0xFF78909C),
                size    = 240.dp,
                onClick = { MonitoringController.toggle(context) }
            )

            Text(
                text       = if (isProtected) "SISTEMA PROTEGIDO" else "MONITORIZACIÓN PAUSADA",
                fontSize   = 26.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text      = if (isProtected)
                        "Toca el escudo para pausar"
                    else
                        "Toca el escudo para reanudar",
                    fontSize  = 14.sp,
                    color     = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isProtected) {
                    Text(
                        text      = "Última probabilidad: ${"%.2f".format(probabilidad * 100)}%",
                        fontSize  = 12.sp,
                        color     = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth()
                    )
                }
            }

            settings?.let { s ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text      = "Contacto de emergencia",
                            fontSize  = 12.sp,
                            color     = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text       = s.contactName.ifBlank { "Sin configurar" },
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color.White,
                            textAlign  = TextAlign.Center,
                            modifier   = Modifier.fillMaxWidth()
                        )
                        Text(
                            text      = s.contactPhone.ifBlank { "—" },
                            fontSize  = 14.sp,
                            color     = Color.White.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text      = "Tiempo de cancelación: ${s.countdownSecs}s",
                            fontSize  = 12.sp,
                            color     = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Escudo vectorial. Borde superior con un pico central pronunciado y dos
 * valles laterales; punta inferior redondeada. Color de relleno
 * configurable y check blanco centrado.
 */
@Composable
private fun ShieldIcon(
    fill: Color,
    size: Dp,
    onClick: () -> Unit
) {
    Canvas(
        modifier = Modifier
            .size(size)
            .clickable(onClick = onClick)
    ) {
        val w = this.size.width
        val h = this.size.height

        val shield = Path().apply {
            // Borde superior: pico central arriba, valles a los lados
            moveTo(w * 0.10f, h * 0.12f)                 // hombro superior izquierdo
            quadraticBezierTo(
                w * 0.27f, h * 0.24f,                    // valle izquierdo (baja)
                w * 0.50f, h * 0.02f                     // pico central (sube)
            )
            quadraticBezierTo(
                w * 0.73f, h * 0.24f,                    // valle derecho (baja)
                w * 0.90f, h * 0.12f                     // hombro superior derecho
            )
            lineTo(w * 0.90f, h * 0.52f)                 // lado derecho
            cubicTo(                                      // curva al pico inferior
                w * 0.90f, h * 0.77f,
                w * 0.71f, h * 0.93f,
                w * 0.50f, h * 0.99f
            )
            cubicTo(                                      // subida lado izquierdo
                w * 0.29f, h * 0.93f,
                w * 0.10f, h * 0.77f,
                w * 0.10f, h * 0.52f
            )
            close()
        }
        drawPath(shield, fill)

        // Check blanco centrado en el cuerpo del escudo
        val check = Path().apply {
            moveTo(w * 0.33f, h * 0.52f)
            lineTo(w * 0.45f, h * 0.65f)
            lineTo(w * 0.69f, h * 0.39f)
        }
        drawPath(
            path  = check,
            color = Color.White,
            style = Stroke(
                width = w * 0.07f,
                cap   = StrokeCap.Round,
                join  = StrokeJoin.Round
            )
        )
    }
}