package com.codexedge.glow.fastpathv1

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.text.Normalizer
import java.util.UUID

/**
 * Offline, fail-closed v1 envelope decoder. No Android or action dependencies.
 * This is NOT the complete M1 JSON-schema validator; payload validation remains
 * mandatory before any receiver may accept an operation.
 */
internal object StrictEnvelopeV1 {
    private const val MAX_BYTES = 65536
    private const val MAX_DEPTH = 128
    private val idKeys = setOf("requestId", "taskId", "sessionId", "ownerId")
    private val topKeys = setOf("protocolVersion", "messageType", "requestId", "taskId",
        "sessionId", "ownerId", "deviceBinding", "expiresAtEpochMs", "payload")
    private val deviceKeys = setOf("deviceSerial", "companionPackage",
        "companionVersionCode", "signerCertificateSha256")
    private val messageTypes = setOf("route.promote", "route.revoke", "route.execute",
        "route.cancel", "route.status", "evidence.capture", "route.verify")
    private val uuid4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val hex64 = Regex("[0-9a-f]{64}")
    private const val MAX_SAFE = 9007199254740991L

    fun parse(json: String): Map<String, Any?> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Envelope too large" }
        val reader = JsonReader(StringReader(json))
        reader.isLenient = false
        val parsed = readValue(reader, 0)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON" }
        require(parsed is Map<*, *>) { "Envelope must be object" }
        @Suppress("UNCHECKED_CAST")
        val envelope = parsed as Map<String, Any?>
        require(envelope.keys == topKeys) { "Unknown or missing envelope member" }
        require(envelope["protocolVersion"] == "tessera.fast-path/1") { "Unsupported protocol" }
        require(envelope["messageType"] in messageTypes) { "Unknown message type" }
        for (key in idKeys) {
            val id = envelope[key] as? String ?: error("Missing $key")
            require(uuid4.matches(id) && UUID.fromString(id).version() == 4) { "Invalid $key" }
        }
        val binding = envelope["deviceBinding"] as? Map<*, *> ?: error("Missing deviceBinding")
        require(binding.keys == deviceKeys) { "Invalid deviceBinding members" }
        require((binding["deviceSerial"] as? String)?.length in 1..128)
        require(binding["companionPackage"] == "com.codexedge.glow")
        require((binding["companionVersionCode"] as? Long)?.let { it > 0 } == true)
        require((binding["signerCertificateSha256"] as? String)?.let { hex64.matches(it) } == true)
        require((envelope["expiresAtEpochMs"] as? Long)?.let { it > 0 } == true)
        require(envelope["payload"] is Map<*, *>) { "Missing payload object" }
        validatePayload(envelope["messageType"] as String, envelope["payload"] as Map<*, *>)
        return envelope
    }

    private val routeId = Regex("[a-z][a-z0-9._-]{0,63}")
    private val reason = Regex("[a-z][a-z0-9-]{0,63}")
    private val pkg = Regex("[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)+")
    private fun validatePayload(type: String, payload: Map<*, *>) {
        val fields = when (type) {
            "route.promote" -> setOf("promotionRecord")
            "route.revoke" -> setOf("revocationRecord")
            "route.execute" -> setOf("routeId", "routeVersion", "routeFingerprint", "routeDefinition")
            "route.cancel" -> setOf("reason")
            "route.status" -> setOf("routeId", "routeVersion", "routeFingerprint", "maxAgeMs")
            "evidence.capture" -> setOf("expectedPackage", "expectedWindowId", "maxAgeMs", "maxNodes")
            "route.verify" -> setOf("executionRequestId", "routeId", "routeVersion", "routeFingerprint",
                "expectedDestinationFingerprint", "expectedPackage", "expectedWindowId", "maxAgeMs")
            else -> error("Unsupported operation")
        }
        require(payload.keys == fields) { "Missing or unexpected payload fields" }
        fun string(key: String, regex: Regex) =
            require((payload[key] as? String)?.let { regex.matches(it) } == true) { "Invalid $key" }
        fun number(key: String, min: Long, max: Long = MAX_SAFE) =
            require((payload[key] as? Long)?.let { it in min..max } == true) { "Invalid $key" }
        if ("routeId" in fields) string("routeId", routeId)
        if ("routeVersion" in fields) number("routeVersion", 1)
        if ("routeFingerprint" in fields) string("routeFingerprint", hex64)
        if ("expectedDestinationFingerprint" in fields) string("expectedDestinationFingerprint", hex64)
        if ("expectedPackage" in fields) string("expectedPackage", pkg)
        if ("expectedWindowId" in fields) number("expectedWindowId", 0)
        if ("maxAgeMs" in fields) number("maxAgeMs", 1, 2500)
        if ("maxNodes" in fields) number("maxNodes", 1, 4096)
        if ("executionRequestId" in fields) string("executionRequestId", uuid4)
        if ("reason" in fields) string("reason", reason)
        if ("routeDefinition" in fields) require(payload["routeDefinition"] is Map<*, *>)
        if ("promotionRecord" in fields) require(payload["promotionRecord"] is Map<*, *>)
        if ("revocationRecord" in fields) require(payload["revocationRecord"] is Map<*, *>)
    }

    private fun validScalars(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                i += 2
            } else {
                if (Character.isLowSurrogate(ch)) return false
                i++
            }
        }
        return true
    }

    private fun readValue(reader: JsonReader, depth: Int): Any? {
        require(depth <= MAX_DEPTH) { "JSON too deeply nested" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val out = linkedMapOf<String, Any?>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(validScalars(key)) { "Invalid Unicode object key" }
                    val normalized = Normalizer.normalize(key, Normalizer.Form.NFC)
                    require(!out.containsKey(normalized)) { "Duplicate JSON member" }
                    out[normalized] = readValue(reader, depth + 1)
                }
                reader.endObject()
                out
            }
            JsonToken.BEGIN_ARRAY -> {
                val out = mutableListOf<Any?>()
                reader.beginArray()
                while (reader.hasNext()) out.add(readValue(reader, depth + 1))
                reader.endArray()
                out
            }
            JsonToken.STRING -> {
                val value = reader.nextString()
                require(validScalars(value)) { "Invalid Unicode surrogate" }
                Normalizer.normalize(value, Normalizer.Form.NFC)
            }
            JsonToken.NUMBER -> {
                val raw = reader.nextString()
                require(Regex("-?(0|[1-9][0-9]*)").matches(raw)) { "Only JSON integers accepted" }
                val number = raw.toLongOrNull() ?: error("Integer overflow")
                require(number in -MAX_SAFE..MAX_SAFE) { "Unsafe integer" }
                number
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); null }
            else -> error("Invalid JSON token")
        }
    }
}
