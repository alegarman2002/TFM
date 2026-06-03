package com.tfm.falldetection.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * EmergencyPreferences
 *
 * Gestiona la persistencia de los ajustes de emergencia mediante DataStore:
 *   - Nombre del contacto de emergencia
 *   - Número de teléfono
 *   - Duración del countdown antes de enviar el SMS (en segundos)
 *
 * Se expone como Flow para observar cambios reactivamente desde la UI Compose.
 */

private val Context.dataStore by preferencesDataStore(name = "emergency_prefs")

class EmergencyPreferences(private val context: Context) {

    companion object {
        private val KEY_CONTACT_NAME    = stringPreferencesKey("contact_name")
        private val KEY_CONTACT_PHONE   = stringPreferencesKey("contact_phone")
        private val KEY_COUNTDOWN_SECS  = intPreferencesKey("countdown_secs")

        const val DEFAULT_COUNTDOWN_SECS = 10
    }

    /**
     * Flow combinado con todos los ajustes. Emite cada vez que cualquiera cambia.
     */
    val settings: Flow<EmergencySettings> = context.dataStore.data.map { prefs ->
        EmergencySettings(
            contactName    = prefs[KEY_CONTACT_NAME]   ?: "",
            contactPhone   = prefs[KEY_CONTACT_PHONE]  ?: "",
            countdownSecs  = prefs[KEY_COUNTDOWN_SECS] ?: DEFAULT_COUNTDOWN_SECS
        )
    }

    suspend fun save(
        name: String,
        phone: String,
        countdownSecs: Int
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONTACT_NAME]   = name.trim()
            prefs[KEY_CONTACT_PHONE]  = phone.trim()
            prefs[KEY_COUNTDOWN_SECS] = countdownSecs.coerceIn(5, 30)
        }
    }
}

data class EmergencySettings(
    val contactName: String,
    val contactPhone: String,
    val countdownSecs: Int
) {
    /** Devuelve true si la configuración es válida para poder enviar SMS. */
    val isConfigured: Boolean
        get() = contactName.isNotBlank() &&
                contactPhone.length >= 9 &&
                contactPhone.all { it.isDigit() || it == '+' }
}