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
        require(!json.any { it in '\uD800'..'\uDFFF' }) { "Raw unpaired/escaped surrogate input must be validated" }
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
        require((binding["deviceSerial"] as? String)?.isNotBlank() == true)
        require(binding["companionPackage"] == "com.codexedge.glow")
        require((binding["companionVersionCode"] as? Long)?.let { it > 0 } == true)
        require((binding["signerCertificateSha256"] as? String)?.let { hex64.matches(it) } == true)
        require((envelope["expiresAtEpochMs"] as? Long)?.let { it > 0 } == true)
        require(envelope["payload"] is Map<*, *>) { "Missing payload object" }
        return envelope
    }

    private fun readValue(reader: JsonReader, depth: Int): Any? {
        require(depth <= 64) { "JSON too deeply nested" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val out = linkedMapOf<String, Any?>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
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
                require(!value.any { it in '\uD800'..'\uDFFF' }) { "Invalid Unicode surrogate" }
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
