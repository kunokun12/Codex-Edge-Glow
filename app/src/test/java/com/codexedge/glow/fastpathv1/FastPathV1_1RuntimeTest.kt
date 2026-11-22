package com.codexedge.glow.fastpathv1

import org.junit.Assert.*
import org.junit.Test

class FastPathV1_1RuntimeTest {
    private val binding = mapOf("deviceSerial" to "offline-test", "companionPackage" to "com.codexedge.glow",
        "companionVersionCode" to 18L, "signerCertificateSha256" to "a".repeat(64))
    private fun request(operation: String) = mapOf<String, Any?>(
        "protocolVersion" to ExperimentalEnvelopeV1_1.VERSION, "messageType" to operation,
        "requestId" to "00000000-0000-4000-8000-000000000001", "taskId" to "00000000-0000-4000-8000-000000000002",
        "sessionId" to "00000000-0000-4000-8000-000000000003", "ownerId" to "00000000-0000-4000-8000-000000000004",
        "deviceBinding" to binding)

    @Test fun executeReturnsTypedRefusalWithoutCrossingDispatchBoundary() {
        val response = FastPathV1_1Runtime.dispatch(request("route.execute"), 1900000000000L)
        assertEquals("route.execute.result", response["messageType"])
        assertEquals("REFUSED", response["status"])
        assertEquals("runtime-approval-required", response["reasonCode"])
        val execution = response["execution"] as Map<*, *>
        assertEquals(false, execution["dispatchPossible"])
        assertNull(execution["actionReceipt"])
        assertNull(response["provenance"])
        FastPathV1_1Runtime.validateResponse(response)
    }

    @Test fun captureUnavailableHasNoFabricatedEvidence() {
        val response = FastPathV1_1Runtime.dispatch(request("evidence.capture"), 1900000000000L)
        assertEquals("evidence.capture.result", response["messageType"])
        val payload = response["payload"] as Map<*, *>
        assertEquals("OBSERVER_UNAVAILABLE", payload["captureOutcome"])
        assertNull(payload["snapshot"])
        assertNull(payload["producer"])
        FastPathV1_1Runtime.validateResponse(response)
    }

    @Test fun verificationUnavailableCannotClaimVerified() {
        val response = FastPathV1_1Runtime.dispatch(request("route.verify"), 1900000000000L)
        assertEquals("route.verify.result", response["messageType"])
        assertEquals("REFUSED", response["status"])
        val payload = response["payload"] as Map<*, *>
        assertEquals("UNAVAILABLE", payload["outcome"])
        assertNull(payload["evidence"])
        FastPathV1_1Runtime.validateResponse(response)
    }

    @Test fun malformedCaptureCannotAttachInventedEvidenceToFailure() {
        val badPorts = object : V1_1RuntimePorts {
            override val executionGateApproved = false
            override val authorityAvailable = false
            override val observerApproved = false
            override fun capture(request: Map<String, Any?>) = CaptureResult("OBSERVER_UNAVAILABLE", "observer-unavailable", mapOf("invented" to true), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FastPathV1_1Runtime.dispatch(request("evidence.capture"), 1900000000000L, badPorts)
        }
    }

    @Test fun exactProfileAndOperationPairingAreMandatory() {
        val req = request("evidence.capture").toMutableMap().apply { put("protocolVersion", "tessera.fast-path/1") }
        assertThrows(IllegalArgumentException::class.java) { FastPathV1_1Runtime.dispatch(req, 1900000000000L) }
        // Correlation checker must reject the mismatched pair.
        val response = FastPathV1_1Runtime.dispatch(request("route.verify"), 1900000000000L).toMutableMap().apply { put("messageType", "route.execute.result") }
        assertEquals("response-operation-mismatch", ExperimentalEnvelopeV1_1.validateResponseCorrelation(request("route.verify"), response))
    }

    @Test fun proposedCaptureFailureGoldenVectorsAgreeWithHostSchema() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("fastpathv1/v1.1-evidence-response-vectors.json")!!
            .bufferedReader().use { it.readText() }
        val vectors = com.google.gson.JsonParser.parseString(fixture).asJsonObject.getAsJsonArray("vectors")
        assertEquals(6, vectors.size())
        vectors.forEach { item ->
            val vector = item.asJsonObject
            val request = jsonMap(vector.getAsJsonObject("request"))
            val response = jsonMap(vector.getAsJsonObject("response"))
            assertNull(ExperimentalEnvelopeV1_1.validateResponseCorrelation(request, response))
            FastPathV1_1Runtime.validateResponse(response)
            val payload = response["payload"] as Map<*, *>
            assertNull(payload["snapshot"])
            assertNull(payload["producer"])
        }
    }

    private fun jsonMap(value: com.google.gson.JsonObject): Map<String, Any?> = value.entrySet().associate { it.key to jsonValue(it.value) }
    private fun jsonValue(value: com.google.gson.JsonElement): Any? = when {
        value.isJsonNull -> null
        value.isJsonObject -> jsonMap(value.asJsonObject)
        value.isJsonArray -> value.asJsonArray.map(::jsonValue)
        value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.asJsonPrimitive.isNumber -> value.asLong
        else -> value.asString
    }

}
