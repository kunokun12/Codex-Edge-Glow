package com.codexedge.glow.fastpathv1

import org.junit.Assert.*
import org.junit.Test

class ExperimentalEnvelopeV1_1Test {
    private fun request(version: String = ExperimentalEnvelopeV1_1.VERSION): String = """
        {"protocolVersion":"$version","messageType":"route.cancel",
         "requestId":"00000000-0000-4000-8000-000000000001",
         "taskId":"00000000-0000-4000-8000-000000000002",
         "sessionId":"00000000-0000-4000-8000-000000000003",
         "ownerId":"00000000-0000-4000-8000-000000000004",
         "deviceBinding":{"deviceSerial":"offline-test","companionPackage":"com.codexedge.glow","companionVersionCode":18,"signerCertificateSha256":"${"a".repeat(64)}"},
         "expiresAtEpochMs":1900000000000,"payload":{"reason":"owner-request"}}
    """.trimIndent()

    @Test fun acceptsOnlyExactExperimentalProfile() {
        assertEquals(ExperimentalEnvelopeV1_1.VERSION,
            ExperimentalEnvelopeV1_1.parseRequest(request())["protocolVersion"])
        try {
            ExperimentalEnvelopeV1_1.parseRequest(request("tessera.fast-path/1"))
            fail("v1 downgrade accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsDuplicateAndNfcCollidingJsonMembers() {
        val duplicate = request().replace("\"protocolVersion\":\"tessera.fast-path/1.1\",", "\"protocolVersion\":\"tessera.fast-path/1.1\",\"protocolVersion\":\"tessera.fast-path/1.1\",")
        assertThrows(IllegalArgumentException::class.java) { ExperimentalEnvelopeV1_1.parseRequest(duplicate) }
        val collision = "{\"é\":1,\"é\":2}"
        assertThrows(IllegalArgumentException::class.java) { StrictEnvelopeV1.parseExperimentalV1_1(collision) }
    }

    @Test fun responseTypeIsOperationSpecificAndNoLegacyRouteResultAliasExists() {
        assertEquals("route.execute.result", ExperimentalEnvelopeV1_1.expectedResponseType("route.execute"))
        assertNull(ExperimentalEnvelopeV1_1.expectedResponseType("route.result"))
    }

    @Test fun canonicalSerializerNormalizesUnicodeDeterministically() {
        val bytes = ExperimentalEnvelopeV1_1.canonicalBytes(mapOf("é" to "Café"))
        assertEquals("{\"é\":\"Café\"}", String(bytes, Charsets.UTF_8))
    }

    @Test fun consumesSharedV11ConformanceVectorAndPairsItsResponse() {
        val json = javaClass.classLoader!!.getResourceAsStream("fastpathv1/v1.1-conformance-vectors.json")!!
            .bufferedReader().use { it.readText() }
        val fixture = com.google.gson.JsonParser.parseString(json).asJsonObject
        val vector = fixture.getAsJsonArray("vectors")[0].asJsonObject
        val request = ExperimentalEnvelopeV1_1.parseRequest(vector.getAsJsonObject("request").toString())
        assertEquals(vector.get("expectedResponseType").asString, ExperimentalEnvelopeV1_1.expectedResponseType(request.getValue("messageType") as String))
        val response = request.toMutableMap().apply { put("messageType", vector.get("expectedResponseType").asString) }
        assertNull(ExperimentalEnvelopeV1_1.validateResponseCorrelation(request, response))
        assertEquals("route.execute.result", ExperimentalEnvelopeV1_1.expectedResponseType("route.execute"))
        assertNotEquals("route.result", ExperimentalEnvelopeV1_1.expectedResponseType("route.execute"))
    }
}
