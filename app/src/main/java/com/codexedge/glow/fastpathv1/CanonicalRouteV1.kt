package com.codexedge.glow.fastpathv1

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Pure JVM canonical JSON for the Tessera route-canonical/1 fingerprint.
 * Intentionally not wired to receivers, route promotion, or ACTION_CLICK.
 * Callers must first parse JSON with a strict duplicate-key/UTF-8 validator.
 */
internal object CanonicalRouteV1 {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(Normalizer.normalize(value, Normalizer.Form.NFC))
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float, is Double, is java.math.BigDecimal -> error("Non-integer JSON number")
        is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { encode(it) }
        is Map<*, *> -> {
            require(value.keys.all { it is String }) { "Object keys must be strings" }
            val normalized = value.entries.map { Normalizer.normalize(it.key as String, Normalizer.Form.NFC) to it.value }
            require(normalized.map { it.first }.toSet().size == normalized.size) { "Duplicate normalized keys" }
            normalized.sortedWith(Comparator { a, b -> compareCodepoints(a.first, b.first) })
                .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, item) ->
                    quote(key) + ":" + encode(item)
                }
        }
        else -> error("Unsupported canonical JSON type: ${value::class.java.name}")
    }

    fun fingerprint(routeId: String, routeVersion: Int, routeDefinition: Map<String, Any?>): String {
        require(routeVersion > 0)
        val canonical = encode(mapOf(
            "fingerprintVersion" to "tessera.route-canonical/1",
            "routeId" to routeId,
            "routeVersion" to routeVersion,
            "routeDefinition" to routeDefinition
        ))
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun compareCodepoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ac = a.codePointAt(i)
            val bc = b.codePointAt(j)
            if (ac != bc) return ac.compareTo(bc)
            i += Character.charCount(ac)
            j += Character.charCount(bc)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun quote(value: String): String = buildString {
        append('"')
        var index = 0
        while (index < value.length) {
            val cp = value.codePointAt(index)
            require(cp !in 0xD800..0xDFFF) { "Unpaired surrogate" }
            when (cp) {
                0x22 -> append("\\\"")
                0x5C -> append("\\\\")
                0x08 -> append("\\b")
                0x0C -> append("\\f")
                0x0A -> append("\\n")
                0x0D -> append("\\r")
                0x09 -> append("\\t")
                in 0..0x1F -> append("\\u%04x".format(cp))
                else -> appendCodePoint(cp)
            }
            index += Character.charCount(cp)
        }
        append('"')
    }
}
