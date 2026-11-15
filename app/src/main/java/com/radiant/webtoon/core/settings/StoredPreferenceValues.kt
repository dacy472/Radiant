package com.radiant.webtoon.core.settings

/**
 * Coerces preference values that were stored under a different runtime type or an older key.
 * DataStore and SharedPreferences both throw [ClassCastException] when a key is read as the
 * wrong type, which takes down startup after an upgrade.
 */
object StoredPreferenceValues {
    enum class Kind { STRING, BOOLEAN, STRING_SET }

    fun asString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Long -> value.toString()
        is Float -> value.toString()
        is Double -> value.toString()
        is Set<*> -> value.mapNotNull { it?.toString() }.joinToString(",").ifEmpty { null }
        else -> null
    }

    fun asBoolean(value: Any?): Boolean? = when (value) {
        null -> null
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        is Int -> value != 0
        is Long -> value != 0L
        is Float -> value != 0f
        is Double -> value != 0.0
        else -> null
    }

    fun asStringSet(value: Any?): Set<String>? = when (value) {
        null -> null
        is Set<*> -> value.mapNotNull { it?.toString() }.toSet()
        is String -> if (value.isEmpty()) emptySet() else setOf(value)
        else -> value.toString().let { setOf(it) }
    }

    fun asInt(value: Any?, default: Int): Int = when (value) {
        null -> default
        is Int -> value
        is Long -> value.toInt()
        is Boolean -> if (value) 1 else 0
        is String -> value.trim().toIntOrNull() ?: default
        is Float -> value.toInt()
        is Double -> value.toInt()
        else -> default
    }

    fun coerce(value: Any?, kind: Kind): Any? = when (kind) {
        Kind.STRING -> asString(value)
        Kind.BOOLEAN -> asBoolean(value)
        Kind.STRING_SET -> asStringSet(value)
    }

    /**
     * Entries whose stored type does not match [kinds]. Correct values are left alone.
     * Keys that cannot be coerced are omitted so a bad value is not replaced with garbage.
     */
    fun rewrites(stored: Map<String, Any?>, kinds: Map<String, Kind>): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        for ((name, kind) in kinds) {
            if (!stored.containsKey(name)) continue
            val raw = stored[name]
            if (matches(raw, kind)) continue
            val coerced = coerce(raw, kind) ?: continue
            out[name] = coerced
        }
        return out
    }

    /** Dynamic string keys such as `reading_mode_override:<series>` that were stored as another type. */
    fun prefixedStringRewrites(stored: Map<String, Any?>, prefix: String): Map<String, String> {
        if (prefix.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for ((name, raw) in stored) {
            if (!name.startsWith(prefix) || raw is String) continue
            val coerced = asString(raw) ?: continue
            out[name] = coerced
        }
        return out
    }

    /**
     * Copies [aliases] (`new key` → older key names) when the new key is absent.
     * No shipped Radiant key has been renamed; callers pass an empty map unless one is found.
     */
    fun legacyCopies(
        stored: Map<String, Any?>,
        aliases: Map<String, List<String>>,
        kinds: Map<String, Kind>,
    ): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        for ((newName, oldNames) in aliases) {
            if (stored.containsKey(newName)) continue
            val kind = kinds[newName] ?: continue
            val oldName = oldNames.firstOrNull { stored.containsKey(it) } ?: continue
            val coerced = coerce(stored[oldName], kind) ?: continue
            out[newName] = coerced
        }
        return out
    }

    private fun matches(raw: Any?, kind: Kind): Boolean = when (kind) {
        Kind.STRING -> raw is String
        Kind.BOOLEAN -> raw is Boolean
        Kind.STRING_SET -> raw is Set<*>
    }
}
