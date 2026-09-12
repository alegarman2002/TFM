package com.tfm.falldetection.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tfm.falldetection.mobile.data.EmergencyPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ConfigScreen
 *
 * Configuración del contacto de emergencia (nombre, teléfono, countdown).
 * Incluye además un acceso discreto al modo de recogida de datos
 * (=== RECOGIDA DE DATOS ===), pensado solo para construir el dataset de
 * entrenamiento y no para el producto final.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onSaved: () -> Unit,
    onOpenDataCollection: () -> Unit = {}   // === RECOGIDA DE DATOS ===
) {
    val context = LocalContext.current
    val prefs = remember { EmergencyPreferences(context) }
    val scope = rememberCoroutineScope()

    var name        by remember { mutableStateOf("") }
    var phone       by remember { mutableStateOf("") }
    var countdown   by remember { mutableStateOf("10") }
    var errorMsg    by remember { mutableStateOf<String?>(null) }
    var isLoading   by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val current = prefs.settings.first()
        name      = current.contactName
        phone     = current.contactPhone
        countdown = current.countdownSecs.toString()
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text       = "⚙ CONFIGURACIÓN",
            fontSize   = 24.sp,
            fontWeight = FontWeight.Bold,
            color      = Color.White,
            textAlign  = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text      = "Define el contacto al que se enviará una alerta automática en caso de caída.",
            fontSize  = 14.sp,
            color     = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (isLoading) {
            CircularProgressIndicator(color = Color.White)
            return@Column
        }

        OutlinedTextField(
            value         = name,
            onValueChange = { name = it },
            label         = { Text("Nombre del contacto") },
            placeholder   = { Text("Ej: María (hija)") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            colors        = whiteFieldColors()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value         = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' } },
            label         = { Text("Número de teléfono") },
            placeholder   = { Text("Ej: +34612345678") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors        = whiteFieldColors()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value         = countdown,
            onValueChange = { countdown = it.filter { c -> c.isDigit() }.take(2) },
            label         = { Text("Tiempo de espera (segundos)") },
            placeholder   = { Text("10") },
            supportingText = { Text("Entre 5 y 30 segundos. Tiempo para cancelar la alerta antes del envío.") },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors        = whiteFieldColors()
        )

        Spacer(modifier = Modifier.height(24.dp))

        errorMsg?.let {
            Text(
                text     = it,
                color    = Color(0xFFFF8A80),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = {
                val secs = countdown.toIntOrNull()
                when {
                    name.isBlank() ->
                        errorMsg = "Introduce un nombre para el contacto."
                    phone.length < 9 ->
                        errorMsg = "El número debe tener al menos 9 dígitos."
                    secs == null || secs !in 5..30 ->
                        errorMsg = "El tiempo debe estar entre 5 y 30 segundos."
                    else -> {
                        errorMsg = null
                        scope.launch {
                            prefs.save(name, phone, secs)
                            onSaved()
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1976D2),
                contentColor   = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text     = "GUARDAR Y CONTINUAR",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // === RECOGIDA DE DATOS ===
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onOpenDataCollection) {
            Text(
                text  = "Modo recogida de datos (entrenamiento)",
                color = Color(0xFF80CBC4),
                fontSize = 13.sp
            )
        }
        // === FIN RECOGIDA DE DATOS ===

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun whiteFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor       = Color.White,
    unfocusedTextColor     = Color.White,
    focusedBorderColor     = Color(0xFF64B5F6),
    unfocusedBorderColor   = Color.White.copy(alpha = 0.4f),
    focusedLabelColor      = Color(0xFF64B5F6),
    unfocusedLabelColor    = Color.White.copy(alpha = 0.6f),
    focusedSupportingTextColor   = Color.White.copy(alpha = 0.6f),
    unfocusedSupportingTextColor = Color.White.copy(alpha = 0.6f),
    focusedPlaceholderColor      = Color.White.copy(alpha = 0.3f),
    unfocusedPlaceholderColor    = Color.White.copy(alpha = 0.3f),
    cursorColor = Color(0xFF64B5F6)
)