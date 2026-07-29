package com.eklab.adblocker.ui.common

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Human-readable byte count, e.g. "512 B", "1.5 MB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024.0 && unit < units.lastIndex)
    return "%.1f %s".format(value, units[unit])
}

/** Coarse relative time for feed rows, e.g. "just now", "5m ago", "3d ago". */
fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - timestamp).coerceAtLeast(0L)) / 1000L
    val minutes = seconds / 60L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        seconds < 60L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        else -> "${days}d ago"
    }
}

private val timestampFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss")

/** Full timestamp for detail views. */
fun formatTimestamp(timestamp: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
        .format(timestampFormatter)
