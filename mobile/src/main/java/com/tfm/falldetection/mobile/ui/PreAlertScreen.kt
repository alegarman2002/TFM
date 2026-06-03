package com.tfm.falldetection.mobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tfm.falldetection.mobile.EmergencyManager
import com.tfm.falldetection.mobile.data.EmergencyPreferences

/**
 * PreAlertScreen
 *
 * Pantalla que se muestra mientras el sistema está en estado PRE_ALERTA
 * o RESOLUCION_SOS.
 *
 *   - Fondo rojo intenso para captar la atención.
 *   - Countdown circular animado: un anillo se vacía a medida que pasan los segundos.
 *   - Botón gigante "ESTOY BIEN" que cancela el envío.
 *   - Si llega a 0s, el botón se sustituye por "AVISO ENVIADO" durante unos
 *     segundos antes de volver al dashboard.
 *
 * Diseñada para usuarios mayores: tipografía grande, contraste alto,
 * un solo botón principal y nada de elementos distractores.
 */
@Composable
fun PreAlertScreen(onDismissed: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { EmergencyManager.getInstance(context) }
    val prefs   = remember { EmergencyPreferences(context) }

    val estado       by manager.estado.collectAsState()
    val secondsLeft  by manager.secondsLeft.collectAsState()
    val settings     by prefs.settings.collectAsState(initial = null)

    // Volver al dashboard cuando el EmergencyManager vuelve a PROTEGIDO
    LaunchedEffect(estado) {
        if (estado == EmergencyManager.Estado.PROTEGIDO) {
            onDismissed()
        }
    }

    val backgroundColor = when (estado) {
        EmergencyManager.Estado.RESOLUCION_SOS -> Color(0xFF6A1B9A)
        else                                   -> Color(0xFFC62828)
    }

    val totalSecs = settings?.countdownSecs ?: 10

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {

            Text(
                text = when (estado) {
                    EmergencyManager.Estado.RESOLUCION_SOS -> "ENVIANDO ALERTA"
                    else                                   -> "¿ESTÁS BIEN?"
                },
                fontSize   = 36.sp,
                fontWeight = FontWeight.Black,
                color      = Color.White,
                textAlign  = TextAlign.Center
            )

            Text(
                text = when (estado) {
                    EmergencyManager.Estado.RESOLUCION_SOS ->
                        "Se está enviando un mensaje\nde aviso al contacto"
                    else ->
                        "Pulsa el botón si todo está bien.\nDe lo contrario, se avisará\nal contacto en…"
                },
                fontSize  = 18.sp,
                color     = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )

            // Reloj circular con el countdown
            if (estado == EmergencyManager.Estado.PRE_ALERTA) {
                CountdownRing(
                    secondsLeft = secondsLeft,
                    totalSecs   = totalSecs
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { manager.cancelar() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor   = Color(0xFFC62828)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    Text(
                        text       = "ESTOY BIEN",
                        fontSize   = 28.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            } else {
                // Estado RESOLUCION_SOS: spinner mientras se manda el SMS
                CircularProgressIndicator(
                    color       = Color.White,
                    strokeWidth = 6.dp,
                    modifier    = Modifier.size(96.dp)
                )

                Text(
                    text     = "Avisando a ${settings?.contactName ?: ""}…",
                    fontSize = 18.sp,
                    color    = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Anillo circular grande con el número de segundos en el centro.
 * Se anima suavemente entre transiciones de un segundo.
 */
@Composable
private fun CountdownRing(secondsLeft: Int, totalSecs: Int) {
    val fraction = (secondsLeft.toFloat() / totalSecs.coerceAtLeast(1))
        .coerceIn(0f, 1f)

    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "countdown"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(220.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthPx = 18.dp.toPx()
            val padding       = strokeWidthPx / 2
            val arcSize       = Size(size.width - strokeWidthPx, size.height - strokeWidthPx)
            val topLeft       = Offset(padding, padding)

            // Pista de fondo
            drawArc(
                color     = Color.White.copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter  = false,
                topLeft    = topLeft,
                size       = arcSize,
                style      = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            // Anillo de progreso
            drawArc(
                color      = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * animatedFraction,
                useCenter  = false,
                topLeft    = topLeft,
                size       = arcSize,
                style      = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )
        }

        Text(
            text       = "$secondsLeft",
            fontSize   = 96.sp,
            fontWeight = FontWeight.Black,
            color      = Color.White
        )
    }
}