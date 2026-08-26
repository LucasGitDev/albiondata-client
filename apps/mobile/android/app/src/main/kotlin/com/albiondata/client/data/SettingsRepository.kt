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
        private val KEY_INGEST_URL   = stringPreferencesKey("ingest_url")
        private val KEY_REALM        = stringPreferencesKey("realm")
        private val KEY_USER_EMAIL   = stringPreferencesKey("user_email")

        private const val PREF_FILE       = "secure_prefs"
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
        val realm = realmFromKey(prefs[KEY_REALM] ?: Realm.WEST.name)
        val ingestUrl = if (privateMode) {
            prefs[KEY_INGEST_URL] ?: DEFAULT_PRIVATE_URL
        } else {
            realm.powUrl
        }
        val userEmail = prefs[KEY_USER_EMAIL]
        val authToken = readAuthToken()
        AppSettings(
            privateMode = privateMode,
            realm = realm,
            ingestUrl = ingestUrl,
            authToken = authToken,
            userEmail = userEmail,
        )
    }

    suspend fun setPrivateMode(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PRIVATE_MODE] = enabled
            if (!enabled) {
                // Leaving private mode: clear credentials and reset URL to realm default.
                prefs.remove(KEY_USER_EMAIL)
                prefs.remove(KEY_INGEST_URL)
            }
        }
        if (!enabled) clearAuthToken()
    }

    suspend fun setRealm(realm: Realm) {
        context.dataStore.edit { prefs -> prefs[KEY_REALM] = realm.name }
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
