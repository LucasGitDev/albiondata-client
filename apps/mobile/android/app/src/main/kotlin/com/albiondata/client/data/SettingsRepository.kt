package com.albiondata.client.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Persists non-secret settings via DataStore and auth token via EncryptedSharedPreferences.
 *
 * Design note: DataStore holds mode + URL (non-sensitive). Auth token (sensitive) lives in
 * EncryptedSharedPreferences backed by Android Keystore (AES-256-GCM). This matches
 * decision-13 / spike TASK-11.14.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_PRIVATE_MODE = booleanPreferencesKey("private_mode")
        private val KEY_INGEST_URL = stringPreferencesKey("ingest_url")
        private val KEY_USER_EMAIL = stringPreferencesKey("user_email")

        private const val PREF_FILE = "secure_prefs"
        private const val PREF_AUTH_TOKEN = "auth_token"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val privateMode = prefs[KEY_PRIVATE_MODE] ?: false
        val ingestUrl = prefs[KEY_INGEST_URL]
            ?: if (privateMode) DEFAULT_PRIVATE_URL else DEFAULT_AODP_URL
        val userEmail = prefs[KEY_USER_EMAIL]
        val authToken = readAuthToken()
        AppSettings(
            privateMode = privateMode,
            ingestUrl = ingestUrl,
            authToken = authToken,
            userEmail = userEmail,
        )
    }

    suspend fun setPrivateMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val wasPrivate = prefs[KEY_PRIVATE_MODE] ?: false
            prefs[KEY_PRIVATE_MODE] = enabled
            // Reset URL to matching default when toggling mode, unless user customised it.
            val current = prefs[KEY_INGEST_URL]
            val wasDefault = current == null ||
                current == DEFAULT_AODP_URL ||
                current == DEFAULT_PRIVATE_URL
            if (wasDefault) {
                prefs[KEY_INGEST_URL] = if (enabled) DEFAULT_PRIVATE_URL else DEFAULT_AODP_URL
            }
            if (!enabled) {
                // Leaving private mode: clear credentials.
                prefs.remove(KEY_USER_EMAIL)
            }
        }
        if (!enabled) clearAuthToken()
    }

    suspend fun setIngestUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[KEY_INGEST_URL] = url }
    }

    suspend fun saveAuthToken(token: String, email: String) {
        encryptedPrefs.edit().putString(PREF_AUTH_TOKEN, token).apply()
        context.dataStore.edit { prefs -> prefs[KEY_USER_EMAIL] = email }
    }

    suspend fun clearAuthToken() {
        encryptedPrefs.edit().remove(PREF_AUTH_TOKEN).apply()
        context.dataStore.edit { prefs -> prefs.remove(KEY_USER_EMAIL) }
    }

    fun readAuthToken(): String? = encryptedPrefs.getString(PREF_AUTH_TOKEN, null)
}
