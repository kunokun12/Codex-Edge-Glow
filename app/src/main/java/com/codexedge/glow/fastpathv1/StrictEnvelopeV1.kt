package com.codexedge.glow.fastpathv1

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.UUID

/**
 * Offline, fail-closed v1 request-envelope and closed payload validator. It has no
 * Android or action dependencies; authorization and durable mutation remain
 * separate gates.
 */
internal object StrictEnvelopeV1 {
    const val VERSION = "tessera.fast-path/1"
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

    /**
     * Strict transport entry point. A receiver must use this on decoded base64url bytes,
     * never String(bytes) which silently replaces malformed UTF-8.
     * The clock parameter is the Companion's wall clock at admission.
     */
    fun parseTransport(bytes: ByteArray, nowEpochMs: Long): Map<String, Any?> {
        require(bytes.size <= MAX_BYTES) { "Envelope too large" }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val json = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        val envelope = parse(json)
        val expires = envelope["expiresAtEpochMs"] as Long
        require(nowEpochMs >= 0 && expires > nowEpochMs &&
            expires - nowEpochMs <= 300000L) { "Expired or excessive request TTL" }
        return envelope
    }

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
        require((binding["deviceSerial"] as? String)?.codePointCount(0, (binding["deviceSerial"] as? String)?.length ?: 0) in 1..128)
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
    private val pkg = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    private fun validatePayload(type: String, payload: Map<*, *>) {
        val fields = when (type) {
            "route.promote" -> setOf("promotionRecord")
            "route.revoke" -> setOf("revocationRecord")
            "route.execute" -> setOf("routeId", "routeVersion", "routeFingerprint", "routeDefinition")
            "route.cancel" -> setOf("reason")
            "route.status" -> setOf("routeId", "routeVersion", "routeFingerprint", "maxAgeMs",
                "afterSequence", "afterDigest", "maxEvents")
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
        if ("afterSequence" in fields) number("afterSequence", 0)
        if ("afterDigest" in fields) string("afterDigest", hex64)
        if ("maxEvents" in fields) number("maxEvents", 1, 256)
        if ("executionRequestId" in fields) string("executionRequestId", uuid4)
        if ("reason" in fields) string("reason", reason)
        if ("routeDefinition" in fields) {
            validateRoute(payload["routeDefinition"])
            checkFingerprint(payload)
        }
        if ("promotionRecord" in fields) validatePromotion(payload["promotionRecord"])
        if ("revocationRecord" in fields) validateRevocation(payload["revocationRecord"])
    }


    // Closed M1 nested request schemas. These checks establish shape only;
    // signature, fingerprint, epoch and registry authorization are separate gates.
    private val authorityId = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val signature = Regex("[A-Za-z0-9_-]{80,2048}")

    private fun obj(value: Any?, vararg keys: String): Map<*, *> {
        require(value is Map<*, *> && value.keys == keys.toSet()) {
            "Missing or unexpected nested fields"
        }
        return value
    }
    private fun str(obj: Map<*, *>, key: String, pattern: Regex) {
        require((obj[key] as? String)?.let(pattern::matches) == true) { "Invalid $key" }
    }
    private fun bounded(obj: Map<*, *>, key: String, min: Int, max: Int) {
        val value = obj[key] as? String
        require(value != null && value.codePointCount(0, value.length) in min..max) {
            "Invalid $key length"
        }
    }
    private fun positive(obj: Map<*, *>, key: String) {
        require((obj[key] as? Long)?.let { it >= 1 } == true) { "Invalid $key" }
    }
    private fun state(value: Any?) {
        val o = obj(value, "stateFingerprint", "requiredMarkers")
        str(o, "stateFingerprint", hex64)
        val markers = o["requiredMarkers"] as? List<*> ?: error("Invalid requiredMarkers")
        require(markers.isNotEmpty() && markers.size == markers.toSet().size) {
            "Invalid requiredMarkers cardinality"
        }
        markers.forEach {
            require(it is String && it.codePointCount(0, it.length) in 1..120) {
                "Invalid marker"
            }
        }
    }
    private fun validateRoute(value: Any?) {
        val o = obj(value, "packageName", "appConstraint", "origin", "destination",
            "windowPolicy", "steps")
        str(o, "packageName", pkg)
        val app = obj(o["appConstraint"], "versionName", "versionCode", "signerCertificateSha256")
        bounded(app, "versionName", 1, 128)
        positive(app, "versionCode")
        str(app, "signerCertificateSha256", hex64)
        state(o["origin"])
        state(o["destination"])
        require(o["windowPolicy"] == "same_window")
        val steps = o["steps"] as? List<*> ?: error("Invalid steps")
        require(steps.size in 1..32) { "Invalid step count" }
        steps.forEach { item ->
            val step = obj(item, "stepId", "selector", "uniquenessPolicy",
                "expectedBefore", "expectedAfter")
            str(step, "stepId", routeId)
            val selector = obj(step["selector"], "kind", "value")
            require(selector["kind"] in setOf("text", "contentDescription", "resourceId"))
            bounded(selector, "value", 1, 256)
            require(step["uniquenessPolicy"] == "exactly_one_actionable_target")
            state(step["expectedBefore"])
            state(step["expectedAfter"])
        }
    }
    private fun validatePromotion(value: Any?) {
        val o = obj(value, "authorityKeyId", "registryEpoch", "issuedAtEpochMs",
            "expiresAtEpochMs", "routeId", "routeVersion", "routeFingerprint",
            "routeDefinition", "signature")
        str(o, "authorityKeyId", authorityId)
        positive(o, "registryEpoch")
        positive(o, "issuedAtEpochMs")
        positive(o, "expiresAtEpochMs")
        str(o, "routeId", routeId)
        positive(o, "routeVersion")
        str(o, "routeFingerprint", hex64)
        validateRoute(o["routeDefinition"])
        str(o, "signature", signature)
        checkFingerprint(o)
        require((o["expiresAtEpochMs"] as Long) > (o["issuedAtEpochMs"] as Long))
    }

    /** Reusable closed-schema checks for the durable trust boundary. */
    fun validatePromotionRecord(value: Any?) = validatePromotion(value)
    fun validateRevocationRecord(value: Any?) = validateRevocation(value)
    fun validateRouteDefinition(value: Any?) = validateRoute(value)
    private fun checkFingerprint(record: Map<*, *>) {
        @Suppress("UNCHECKED_CAST")
        val definition = record["routeDefinition"] as Map<String, Any?>
        val expected = CanonicalRouteV1.fingerprint(
            record["routeId"] as String,
            record["routeVersion"] as Long,
            definition
        )
        require(record["routeFingerprint"] == expected) { "Route fingerprint mismatch" }
    }

    private fun validateRevocation(value: Any?) {
        val o = obj(value, "authorityKeyId", "registryEpoch", "issuedAtEpochMs",
            "routeId", "routeVersion", "routeFingerprint", "reasonCode", "signature")
        str(o, "authorityKeyId", authorityId)
        positive(o, "registryEpoch")
        positive(o, "issuedAtEpochMs")
        str(o, "routeId", routeId)
        positive(o, "routeVersion")
        str(o, "routeFingerprint", hex64)
        str(o, "reasonCode", reason)
        str(o, "signature", signature)
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
                val normalizedKeys = mutableSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    require(validScalars(key)) { "Invalid Unicode object key" }
                    val normalized = Normalizer.normalize(key, Normalizer.Form.NFC)
                    require(normalizedKeys.add(normalized)) { "Duplicate JSON member after NFC" }
                    out[key] = readValue(reader, depth + 1)
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
                value // Validate M1 schema on original Unicode; NFC only in canonical hashing.
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
