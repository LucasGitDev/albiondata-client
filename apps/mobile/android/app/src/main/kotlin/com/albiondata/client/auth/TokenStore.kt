package com.albiondata.client.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists OAuth tokens using EncryptedSharedPreferences backed by Android Keystore.
 * Plaintext never touches disk; AES-256-GCM encryption is applied transparently.
 */
class TokenStore(context: Context) {

    companion object {
        private const val PREFS_FILE = "auth_prefs"
        private const val KEY_ACCESS_TOKEN = "pref_access_token"
        private const val KEY_REFRESH_TOKEN = "pref_refresh_token"
        private const val KEY_TOKEN_EXPIRY = "pref_token_expiry"
        private const val KEY_USER_EMAIL = "pref_user_email"
        private const val KEY_USER_SUB = "pref_user_sub"
        private const val KEY_INGEST_MODE = "pref_ingest_mode"
        private const val KEY_PRIVATE_URL = "pref_private_url"
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    /** Epoch millis when the access token expires. 0 means unknown. */
    var tokenExpiry: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRY, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    /** Stable Google user identifier — persisted for future server-side use. */
    var userSub: String?
        get() = prefs.getString(KEY_USER_SUB, null)
        set(value) = prefs.edit().putString(KEY_USER_SUB, value).apply()

    /** "public" or "private". Defaults to "public". */
    var ingestMode: String
        get() = prefs.getString(KEY_INGEST_MODE, "public") ?: "public"
        set(value) = prefs.edit().putString(KEY_INGEST_MODE, value).apply()

    var privateUrl: String?
        get() = prefs.getString(KEY_PRIVATE_URL, null)
        set(value) = prefs.edit().putString(KEY_PRIVATE_URL, value).apply()

    /** Returns true when the access token exists and is not expired (with 5 min buffer). */
    fun isAccessTokenValid(): Boolean {
        val token = accessToken ?: return false
        if (token.isBlank()) return false
        val expiry = tokenExpiry
        if (expiry == 0L) return true // no expiry info — optimistically valid
        return System.currentTimeMillis() < expiry - 5 * 60 * 1000
    }

    /** Saves a complete token response from AppAuth or a refresh grant. */
    fun saveTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long?,
        email: String? = null,
        sub: String? = null,
    ) {
        this.accessToken = accessToken
        if (refreshToken != null) this.refreshToken = refreshToken
        this.tokenExpiry = if (expiresInSeconds != null && expiresInSeconds > 0) {
            System.currentTimeMillis() + expiresInSeconds * 1000
        } else 0L
        if (email != null) this.userEmail = email
        if (sub != null) this.userSub = sub
    }

    /** Clears all stored credentials — call on logout. */
    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_SUB)
            .apply()
        ingestMode = "public"
    }
}
