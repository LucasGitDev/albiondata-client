package com.albiondata.client.data

const val DEFAULT_AODP_URL = "https://www.albion-online-data.com/api/v2"
const val DEFAULT_PRIVATE_URL = "https://your-private-api.example.com/api/v2"

/**
 * Snapshot of persisted application settings used by the UI.
 */
data class AppSettings(
    val privateMode: Boolean = false,
    val ingestUrl: String = DEFAULT_AODP_URL,
    /** Encrypted via EncryptedSharedPreferences — stored as opaque string in DataStore. */
    val authToken: String? = null,
    val userEmail: String? = null,
)
