package eu.kanade.tachiyomi.util.lang

import java.util.Locale

fun String.toTitleCase(locale: Locale = Locale.getDefault()): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

fun String.trimAll(): String = trim()

inline fun <T> Iterable<T>.filterNotBlankStrings(): List<String> =
    filterIsInstance<String>().filter { it.isNotBlank() }

fun String?.orEmptyString(): String = this ?: ""

fun String.containsAny(vararg needles: String, ignoreCase: Boolean = true): Boolean =
    needles.any { contains(it, ignoreCase) }
