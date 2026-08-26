package com.albiondata.client.data

// Public POW ingest URLs — scheme triggers Go's httpUploaderPow automatically
const val POW_URL_WEST = "https+pow://pow.west.albion-online-data.com"
const val POW_URL_EAST = "https+pow://pow.east.albion-online-data.com"
const val POW_URL_EU   = "https+pow://pow.europe.albion-online-data.com"

const val DEFAULT_PRIVATE_URL = "https://your-private-api.example.com/api/v2"

enum class Realm(val label: String, val powUrl: String) {
    WEST("West", POW_URL_WEST),
    EAST("East", POW_URL_EAST),
    EU("Europe", POW_URL_EU),
}

fun realmFromKey(key: String): Realm =
    Realm.entries.firstOrNull { it.name == key } ?: Realm.WEST

/**
 * Snapshot of persisted application settings used by the UI.
 */
data class AppSettings(
    val privateMode: Boolean = false,
    val realm: Realm = Realm.WEST,
    val ingestUrl: String = POW_URL_WEST,
    /** Encrypted via EncryptedSharedPreferences — stored as opaque string in DataStore. */
    val authToken: String? = null,
    val userEmail: String? = null,
)
