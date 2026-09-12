package com.tfm.falldetection.mobile.ui

// === RECOGIDA DE DATOS (pantalla completa de entrenamiento, no parte del producto final) ===

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tfm.falldetection.mobile.data.DataCollectionStore
import com.tfm.falldetection.mobile.emergency.WearCommandSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DataCollectionScreen
 *
 * Pantalla dedicada a la recogida de datos para entrenamiento. No forma
 * parte del producto final; se usa para construir un dataset propio.
 *
 * Funciones:
 *   - Iniciar/detener la sesión de recogida (abre sesión en
 *     DataCollectionStore y ordena al reloj el streaming continuo).
 *   - Contadores en vivo de muestras y eventos.
 *   - FASE C: botón de MARCA MANUAL para registrar una caída real que el
 *     modelo NO detectó (falso negativo). Genera un evento
 *     ETIQUETA/CAIDA/marca_manual con el timestamp del momento de pulsado;
 *     el script de conciliación extraerá la ventana de muestras alrededor
 *     de esa marca.
 *
 * Protocolo de uso recomendado para marcar un falso negativo:
 *   1. Realizar la caída (sobre colchoneta).
 *   2. Si en ~5 s el sistema NO entra en pre-alerta, pulsar el botón
 *      "MARCAR CAÍDA NO DETECTADA" inmediatamente.
 *   3. Continuar con la siguiente actividad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataCollectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val active      by DataCollectionStore.active.collectAsState()
    val sampleCount by DataCollectionStore.sampleCount.collectAsState()
    val eventCount  by DataCollectionStore.eventCount.collectAsState()

    // Feedback de la última marca manual
    var lastManualMark by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recogida de datos", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF263238))
            )
        },
        containerColor = Color(0xFF263238)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            Text(
                text       = "Modo entrenamiento",
                fontSize   = 22.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )

            Text(
                text      = "Graba el flujo continuo del acelerómetro del reloj " +
                        "en samples.csv y los eventos etiquetados en events.csv. " +
                        "No forma parte del sistema final.",
                fontSize  = 13.sp,
                color     = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )

            // Estado de la sesión
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (active) Color(0xFF1B5E20) else Color.White.copy(alpha = 0.10f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text      = if (active) "● GRABANDO" else "○ DETENIDO",
                        fontSize  = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color     = Color.White,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth()
                    )
                    if (active) {
                        Text(
                            text      = "session_id: ${DataCollectionStore.currentSessionId}",
                            fontSize  = 11.sp,
                            color     = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text      = "Muestras: $sampleCount",
                        fontSize  = 14.sp,
                        color     = Color.White,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth()
                    )
                    Text(
                        text      = "Eventos etiquetados: $eventCount",
                        fontSize  = 14.sp,
                        color     = Color.White,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth()
                    )
                }
            }

            // Botón iniciar / detener
            Button(
                onClick = {
                    if (active) {
                        WearCommandSender.sendStopCollection(context)
                        DataCollectionStore.endSession()
                        lastManualMark = null
                    } else {
                        DataCollectionStore.startSession(context)
                        WearCommandSender.sendStartCollection(context)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Color(0xFFC62828) else Color(0xFF2E7D32),
                    contentColor   = Color.White
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(
                    text       = if (active) "DETENER RECOGIDA" else "INICIAR RECOGIDA",
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // === FASE C: marca manual de caída no detectada ===
            if (active) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                Text(
                    text      = "Si has realizado una caída y el sistema NO la ha " +
                            "detectado, márcala aquí inmediatamente:",
                    fontSize  = 13.sp,
                    color     = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        DataCollectionStore.appendEvent(
                            timestampMs = now,
                            eventType   = "ETIQUETA",
                            label       = "CAIDA",
                            probability = null,
                            source      = "marca_manual"
                        )
                        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(now))
                        lastManualMark = hora
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF6C00),
                        contentColor   = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().height(72.dp)
                ) {
                    Text(
                        text       = "MARCAR CAÍDA NO DETECTADA",
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center
                    )
                }

                lastManualMark?.let { hora ->
                    Text(
                        text      = "✓ Caída marcada manualmente a las $hora",
                        fontSize  = 13.sp,
                        color     = Color(0xFFFFCC80),
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.fillMaxWidth()
                    )
                }
            }
            // === FIN FASE C ===

            Text(
                text      = "Asegúrate de que el reloj está conectado y con la " +
                        "monitorización activa antes de iniciar.",
                fontSize  = 11.sp,
                color     = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth()
            )

            TextButton(onClick = onBack) {
                Text("Volver", color = Color(0xFF80CBC4))
            }
        }
    }
}