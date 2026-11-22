package com.codexedge.glow.fastpathv1

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/** Atomic v1.1 terminal result store. Duplicate reads return the original response and never rerun a route. */
internal class FastPathV1_1ResultStore(context: Context, directoryName: String = "fastpath-v1-1-results") {
    private val directory = File(context.applicationContext.filesDir, directoryName)

    @Synchronized
    fun existing(request: Map<String, Any?>): Map<String, Any?>? {
        val file = AtomicFile(resultFile(request))
        if (!file.baseFile.exists()) return null
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size in (HEADER_BYTES + 1)..MAX_RESULT_BYTES) { "result-store-size-invalid" }
        val header = bytes.copyOfRange(0, HEADER_BYTES).toString(Charsets.US_ASCII)
        require(header.endsWith("\n")) { "result-store-header-invalid" }
        val requestDigest = header.substring(0, 64)
        val responseDigest = header.substring(64, 128)
        val json = bytes.copyOfRange(HEADER_BYTES, bytes.size).toString(Charsets.UTF_8)
        require(HEX64.matches(requestDigest) && HEX64.matches(responseDigest) &&
            requestDigest == sha256(CanonicalRouteV1.encode(request)) && responseDigest == sha256(json)) {
            "result-store-digest-invalid"
        }
        val response = parseMap(json)
        require(CanonicalRouteV1.encode(response) == json) { "result-store-noncanonical" }
        require(ExperimentalEnvelopeV1_1.validateResponseCorrelation(request, response) == null) {
            "result-store-correlation-invalid"
        }
        FastPathV1_1Runtime.validateResponse(response)
        return response
    }

    @Synchronized
    fun save(request: Map<String, Any?>, response: Map<String, Any?>): Map<String, Any?> {
        existing(request)?.let { prior ->
            require(CanonicalRouteV1.encode(prior) == CanonicalRouteV1.encode(response)) { "request-id-result-conflict" }
            return prior
        }
        require(ExperimentalEnvelopeV1_1.validateResponseCorrelation(request, response) == null) {
            "result-store-correlation-invalid"
        }
        FastPathV1_1Runtime.validateResponse(response)
        val json = CanonicalRouteV1.encode(response)
        val body = json.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_RESULT_BYTES - HEADER_BYTES) { "result-store-size-invalid" }
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("result-directory-unavailable")
        val header = (sha256(CanonicalRouteV1.encode(request)) + sha256(json) + "\n").toByteArray(Charsets.US_ASCII)
        val atomic = AtomicFile(resultFile(request))
        val stream = atomic.startWrite()
        try {
            stream.write(header); stream.write(body); atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
        return response
    }

    private fun resultFile(request: Map<String, Any?>): File {
        val id = request["requestId"] as? String ?: error("request-id-missing")
        require(UUID4.matches(id)) { "request-id-invalid" }
        return File(directory, "${sha256(id)}.json")
    }

    private fun parseMap(json: String): Map<String, Any?> {
        fun value(item: JsonElement): Any? = when {
            item.isJsonNull -> null
            item.isJsonObject -> item.asJsonObject.entrySet().associate { it.key to value(it.value) }
            item.isJsonArray -> item.asJsonArray.map(::value)
            item.asJsonPrimitive.isBoolean -> item.asBoolean
            item.asJsonPrimitive.isNumber -> item.asLong
            else -> item.asString
        }
        return value(JsonParser.parseString(json)) as? Map<String, Any?> ?: error("result-store-shape-invalid")
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val HEADER_BYTES = 129
        private const val MAX_RESULT_BYTES = 1_048_576
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val UUID4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
