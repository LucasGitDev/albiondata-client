package com.albiondata.client.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogEventType { CAPTURE, UPLOAD, ERROR, INFO }

data class LogEvent(
    val id: Long,
    val timestampMs: Long = System.currentTimeMillis(),
    val type: LogEventType,
    val message: String,
) {
    val formattedTime: String
        get() = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestampMs))
}
