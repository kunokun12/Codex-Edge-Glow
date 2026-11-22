package com.codexedge.glow.fastpathv1

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest

/** Per-request atomic results; filenames are request digests, never shared names. */
internal class FastPathResultStoreV1(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "fastpath-v1-results")

    @Synchronized
    fun save(request: Map<String, Any?>, response: Map<String, Any?>): String {
        val requestId = request["requestId"] as String
        val requestDigest = sha256(CanonicalRouteV1.encode(request))
        val file = AtomicFile(resultFile(requestId))
        if (file.baseFile.exists()) {
            val prior = readEnvelope(file)
            if (prior.first != requestDigest) throw IllegalStateException("request-id-body-mismatch")
            return validateResponse(request, prior.second)
        }
        val responseJson = CanonicalRouteV1.encode(response)
        val bytes = responseJson.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RESULT_BYTES) { "response-too-large" }
        validateResponse(request, responseJson)
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("result-directory-unavailable")
        // Fixed-size digest header + canonical response avoids a second JSON
        // escaping layer and keeps the M1 1 MiB response bound exact.
        val responseDigest = sha256(responseJson)
        val envelope = (requestDigest + responseDigest + "\n").toByteArray(Charsets.US_ASCII) + bytes
        val output = file.startWrite()
        try {
            output.write(envelope)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
        return responseJson
    }

    @Synchronized
    fun existingResponse(request: Map<String, Any?>): String? {
        val requestId = request["requestId"] as? String ?: return null
        val file = AtomicFile(resultFile(requestId))
        if (!file.baseFile.exists()) return null
        val prior = readEnvelope(file)
        if (prior.first != sha256(CanonicalRouteV1.encode(request)))
            throw IllegalStateException("request-id-body-mismatch")
        return validateResponse(request, prior.second)
    }

    @Synchronized
    fun read(requestId: String, taskId: String, sessionId: String, ownerId: String): String? {
        if (!UUID4.matches(requestId) || !UUID4.matches(taskId) ||
            !UUID4.matches(sessionId) || !UUID4.matches(ownerId)) return null
        val file = AtomicFile(resultFile(requestId))
        if (!file.baseFile.exists()) return null
        val json = readEnvelope(file).second
        val response = JsonParser.parseString(json).asJsonObject
        validateCanonical(json, response)
        if (response.get("requestId")?.asString != requestId ||
            response.get("taskId")?.asString != taskId ||
            response.get("sessionId")?.asString != sessionId ||
            response.get("ownerId")?.asString != ownerId) return null
        return json
    }

    private fun readEnvelope(file: AtomicFile): Pair<String, String> {
        val raw = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESULT_BYTES + DIGEST_HEADER_BYTES)
                    throw RegistryStorageException("result-file-oversized")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val headerLength = DIGEST_HEADER_BYTES
        if (raw.size <= headerLength || raw[128] != '\n'.code.toByte())
            throw RegistryStorageException("result-file-schema-invalid")
        val digest = raw.copyOfRange(0, 64).toString(Charsets.US_ASCII)
        val responseDigest = raw.copyOfRange(64, 128).toString(Charsets.US_ASCII)
        val response = raw.copyOfRange(headerLength, raw.size).toString(Charsets.UTF_8)
        if (!HEX64.matches(digest) || !HEX64.matches(responseDigest) ||
            response.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES || sha256(response) != responseDigest)
            throw RegistryStorageException("result-file-corrupt")
        val parsed = try { JsonParser.parseString(response).asJsonObject }
            catch (error: Exception) { throw RegistryStorageException("result-file-json-invalid", error) }
        validateCanonical(response, parsed)
        return digest to response
    }

    private fun validateResponse(request: Map<String, Any?>, json: String): String {
        val response = try { JsonParser.parseString(json).asJsonObject }
            catch (error: Exception) { throw RegistryStorageException("result-response-json-invalid", error) }
        validateCanonical(json, response)
        val requestType = request["messageType"] as? String
            ?: throw RegistryStorageException("result-request-type-missing")
        val expectedType = if (requestType == "route.execute") "route.result" else "$requestType.result"
        val binding = request["deviceBinding"] as? Map<String, Any?>
            ?: throw RegistryStorageException("result-request-binding-invalid")
        val expectedBinding = JsonParser.parseString(CanonicalRouteV1.encode(binding))
        if (response.keySet() != RESPONSE_KEYS ||
            response.get("protocolVersion")?.asString != StrictEnvelopeV1.VERSION ||
            response.get("messageType")?.asString != expectedType ||
            response.get("requestId")?.asString != request["requestId"] ||
            response.get("taskId")?.asString != request["taskId"] ||
            response.get("sessionId")?.asString != request["sessionId"] ||
            response.get("ownerId")?.asString != request["ownerId"] ||
            response.get("deviceBinding") != expectedBinding ||
            response.get("status")?.asString?.matches(STATUS) != true ||
            response.get("reasonCode")?.asString?.matches(REASON) != true ||
            response.get("observedAtEpochMs")?.asLong?.let { it > 0 } != true ||
            !response.get("payload").isJsonObject)
            throw RegistryStorageException("result-response-correlation-invalid")
        return json
    }

    private fun validateCanonical(json: String, response: JsonElement) {
        @Suppress("UNCHECKED_CAST")
        fun value(item: JsonElement): Any? = when {
            item.isJsonNull -> null
            item.isJsonObject -> item.asJsonObject.entrySet().associate { it.key to value(it.value) }
            item.isJsonArray -> item.asJsonArray.map(::value)
            item.asJsonPrimitive.isBoolean -> item.asBoolean
            item.asJsonPrimitive.isNumber -> item.asLong
            else -> item.asString
        }
        val canonical = try { CanonicalRouteV1.encode(value(response) as Map<String, Any?>) }
            catch (error: Exception) { throw RegistryStorageException("result-response-noncanonical", error) }
        if (canonical != json) throw RegistryStorageException("result-response-noncanonical")
    }

    private fun resultFile(requestId: String): File {
        require(UUID4.matches(requestId))
        return File(directory, "${sha256(requestId)}.json")
    }

    companion object {
        private const val MAX_RESULT_BYTES = 1_048_576
        private const val DIGEST_HEADER_BYTES = 129
        private val RESPONSE_KEYS = setOf("protocolVersion", "messageType", "requestId", "taskId",
            "sessionId", "ownerId", "deviceBinding", "status", "reasonCode", "observedAtEpochMs", "payload")
        private val STATUS = Regex("[A-Z_]{1,32}")
        private val REASON = Regex("[a-z][a-z0-9-]{0,63}")
        private val UUID4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val HEX64 = Regex("[0-9a-f]{64}")
        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
