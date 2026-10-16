package org.oacp.android

import android.content.Intent

/**
 * Type-safe accessor for OACP action parameters from an intent.
 *
 * Wraps the intent extras and provides typed getters that handle
 * missing values and type coercion gracefully.
 *
 * ```kotlin
 * override fun onAction(context: Context, action: String, params: OacpParams, requestId: String?): OacpResult? {
 *     val query = params.getString("query") ?: return OacpResult.error("missing_parameters", "query is required")
 *     val limit = params.getInt("limit") ?: 10
 *     val verbose = params.getBoolean("verbose") ?: false
 *     // ...
 * }
 * ```
 *
 * Parameter keys are matched by suffix — if the intent has an extra
 * `com.example.app.EXTRA_QUERY`, calling `getString("query")` will find it.
 * This provides build-variant safety for apps using `__APPLICATION_ID__` prefixes.
 */
public class OacpParams(private val intent: Intent) {

    /**
     * Returns all extra keys present in the intent (excluding OACP standard extras).
     */
    public fun keys(): Set<String> {
        val extras = intent.extras ?: return emptySet()
        return extras.keySet()
            .filter { !it.startsWith("org.oacp.extra.") }
            .toSet()
    }

    /**
     * Returns true if a parameter with the given name (or suffix) exists.
     */
    public fun has(name: String): Boolean = resolve(name) != null

    /**
     * Gets a string parameter by name or suffix.
     */
    public fun getString(name: String): String? {
        val key = resolve(name) ?: return null
        return intent.getStringExtra(key)
    }

    /**
     * Gets an integer parameter by name or suffix.
     * Handles both actual int extras and string representations.
     */
    public fun getInt(name: String): Int? {
        val key = resolve(name) ?: return null
        val extras = intent.extras ?: return null

        return when (val value = @Suppress("DEPRECATION") extras.get(key)) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull()
            is Double -> value.toInt()
            else -> null
        }
    }

    /**
     * Gets a long parameter by name or suffix.
     */
    public fun getLong(name: String): Long? {
        val key = resolve(name) ?: return null
        val extras = intent.extras ?: return null

        return when (val value = @Suppress("DEPRECATION") extras.get(key)) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull()
            is Double -> value.toLong()
            else -> null
        }
    }

    /**
     * Gets a boolean parameter by name or suffix.
     * Handles both actual boolean extras and string representations.
     */
    public fun getBoolean(name: String): Boolean? {
        val key = resolve(name) ?: return null
        val extras = intent.extras ?: return null

        return when (val value = @Suppress("DEPRECATION") extras.get(key)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Int -> value != 0
            else -> null
        }
    }

    /**
     * Gets a double parameter by name or suffix.
     */
    public fun getDouble(name: String): Double? {
        val key = resolve(name) ?: return null
        val extras = intent.extras ?: return null

        return when (val value = @Suppress("DEPRECATION") extras.get(key)) {
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    /**
     * Gets a raw value by name or suffix without type coercion.
     */
    @Suppress("DEPRECATION")
    public fun getRaw(name: String): Any? {
        val key = resolve(name) ?: return null
        return intent.extras?.get(key)
    }

    /**
     * Resolves a parameter name to an actual intent extra key.
     *
     * Matching order:
     * 1. Exact match (e.g. "query" matches extra key "query")
     * 2. Suffix match (e.g. "query" matches "com.example.app.EXTRA_QUERY" or "org.example.oacp.extra.QUERY")
     *
     * Suffix matching is case-insensitive on the trailing segment.
     */
    private fun resolve(name: String): String? {
        val extras = intent.extras ?: return null
        val keys = extras.keySet()

        // Exact match first
        if (keys.contains(name)) return name

        // Direct EXTRA_ match (bare key without package prefix, e.g. "EXTRA_AMOUNT")
        val upperName = name.uppercase()
        val extraKey = "EXTRA_$upperName"
        val directMatch = keys.firstOrNull { it.equals(extraKey, ignoreCase = true) }
        if (directMatch != null) return directMatch

        // Suffix match: try suffixes in priority order (most specific first)
        val suffixes = listOf(
            ".EXTRA_$upperName",
            ".extra.$upperName",
            ".extra.${name}",
            ".$upperName",
            ".$name"
        )

        for (suffix in suffixes) {
            val match = keys.firstOrNull { key -> key.endsWith(suffix, ignoreCase = true) }
            if (match != null) return match
        }
        return null
    }
}
