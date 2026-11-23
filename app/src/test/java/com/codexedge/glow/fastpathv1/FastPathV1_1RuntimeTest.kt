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

    @Test fun completeObserverCaptureReturnsSchemaThreeSnapshotAndIndependentProducer() {
        val req = request("evidence.capture") + ("payload" to mapOf(
            "expectedPackage" to "org.example.app", "expectedWindowId" to 1L, "maxAgeMs" to 2500L, "maxNodes" to 4096L))
        val payload = req["payload"] as Map<*, *>
        val snapshotId = "00000000-0000-4000-8000-000000000010"
        val snapshot = mapOf<String, Any?>(
            "schema" to 3L, "snapshotId" to snapshotId, "packageName" to payload["expectedPackage"],
            "source" to "accessibility", "windowId" to payload["expectedWindowId"],
            "windowIdBefore" to payload["expectedWindowId"], "windowIdAfter" to payload["expectedWindowId"],
            "capturedAtEpochMs" to 1800000000000L, "capturedAtElapsedRealtimeMs" to 10L, "ageMs" to 1L,
            "treeComplete" to true, "truncated" to false,
            "nodes" to listOf(mapOf("windowId" to 1L, "parentIndex" to -1L, "depth" to 0L,
                "enabled" to true, "clickable" to false, "visible" to true, "focusable" to false,
                "selected" to false, "checked" to false, "password" to false, "bounds" to listOf(0L, 0L, 10L, 10L))),
            "traversal" to mapOf("rootCount" to 1L, "visitedNodeCount" to 1L, "maxDepth" to 64L,
                "nodeTraversalLimit" to 4096L, "timeBudgetMs" to 250L, "durationMs" to 1L,
                "timeBudgetExceeded" to false, "truncationReason" to null, "traversalError" to null,
                "collectionSuccess" to true, "windowStable" to true, "windowIdBefore" to 1L, "windowIdAfter" to 1L),
            "sha256" to "a".repeat(64))
        val producer = mapOf<String, Any?>(
            "producerId" to "org.tessera.observer.ReadOnlyAccessibilityObserverService",
            "producerPackage" to "org.tessera.observer", "producerUid" to 10566L,
            "producerVersionName" to "0.3.0-schema3-evidence", "producerVersionCode" to 3L,
            "signerCertificateSha256" to ObserverIpcClientV1.EXPECTED_SIGNER_SHA256,
            "deviceSerial" to "offline-test", "taskId" to req["taskId"], "sessionId" to req["sessionId"],
            "snapshotId" to snapshotId, "sourceCommit" to "d".repeat(40), "artifactSha256" to "c".repeat(64))
        val ports = object : V1_1RuntimePorts {
            override val executionGateApproved = false
            override val authorityAvailable = false
            override val observerApproved = false
            override fun capture(request: Map<String, Any?>) = CaptureResult("CAPTURED", "capture-complete", snapshot, producer)
        }
        val response = FastPathV1_1Runtime.dispatch(req, 1800000000000L, ports)
        assertEquals("ACCEPTED", response["status"])
        val result = response["payload"] as Map<*, *>
        assertEquals("CAPTURED", result["captureOutcome"])
        assertEquals(3L, (result["snapshot"] as Map<*, *>)["schema"])
        assertEquals("org.tessera.observer", (result["producer"] as Map<*, *>)["producerPackage"])
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

    @Test fun observerDiagnosticsRemainDistinctAndSchemaValid() {
        val ports = object : V1_1RuntimePorts {
            override val executionGateApproved = false
            override val authorityAvailable = false
            override val observerApproved = false
            override fun capture(request: Map<String, Any?>) = CaptureResult("OBSERVER_UNAVAILABLE", "snapshot-package-mismatch")
        }
        val response = FastPathV1_1Runtime.dispatch(request("evidence.capture"), 1900000000000L, ports)
        assertEquals("snapshot-package-mismatch", response["reasonCode"])
        assertEquals("snapshot-package-mismatch", (response["payload"] as Map<*, *>)["reasonCode"])
        FastPathV1_1Runtime.validateResponse(response)

        val permission = AndroidV1_1RuntimePorts.refusalFor("observer-permission-denied")
        assertEquals("PERMISSION_UNAVAILABLE", permission.outcome)
        assertEquals("observer-permission-denied", permission.reason)
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
        assertEquals(9, vectors.size())
        vectors.forEach { item ->
            val vector = item.asJsonObject
            val request = jsonMap(vector.getAsJsonObject("request"))
            val response = jsonMap(vector.getAsJsonObject("response"))
            assertNull(ExperimentalEnvelopeV1_1.validateResponseCorrelation(request, response))
            FastPathV1_1Runtime.validateResponse(response)
            val payload = response["payload"] as Map<*, *>
            if (payload["captureOutcome"] == "CAPTURED") {
                assertNotNull(payload["snapshot"])
                assertNotNull(payload["producer"])
            } else {
                assertNull(payload["snapshot"])
                assertNull(payload["producer"])
            }
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
