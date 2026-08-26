package com.radiant.webtoon.core.util

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Parse/format chapter upload timestamps from extensions and Room.
 * Display uses an exact calendar date — never relative labels like "Yesterday".
 */
object ChapterDates {

    fun formatExact(millis: Long, pattern: String? = null): String? {
        if (millis <= 0L) return null
        val date = Date(millis)
        return runCatching {
            val formatter = if (!pattern.isNullOrBlank()) {
                SimpleDateFormat(pattern, Locale.getDefault())
            } else {
                DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            }
            formatter.format(date)
        }.getOrNull()
    }

    fun parse(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.takeIf { it > 1_000_000_000L }?.let { epoch ->
            return if (epoch < 10_000_000_000L) epoch * 1000L else epoch
        }
        for (pattern in PARSE_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = true
                    timeZone = TimeZone.getDefault()
                }.parse(value)?.time
            }.getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        return null
    }

    fun millisFrom(rawDate: String?, millis: Long = 0L): Long {
        if (millis > 0L) return millis
        return parse(rawDate) ?: 0L
    }

    private val PARSE_PATTERNS = listOf(
        "yyyy-MM-dd",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "MMM dd, yyyy",
        "MMM d, yyyy",
        "MMMM dd, yyyy",
        "MMMM d, yyyy",
        "MM/dd/yyyy",
        "dd/MM/yyyy",
        "dd-MM-yyyy",
        "yyyy/MM/dd",
    )
}
