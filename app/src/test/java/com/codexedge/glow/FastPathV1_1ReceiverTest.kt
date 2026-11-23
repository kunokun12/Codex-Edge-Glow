package com.codexedge.glow

import android.app.Activity
import android.content.Intent
import android.util.Base64
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class FastPathV1_1ReceiverTest {
    private fun envelope(profile: String = "tessera.fast-path/1.1", operation: String = "evidence.capture"): String {
        val now = System.currentTimeMillis()
        return Gson().toJson(mapOf(
            "protocolVersion" to profile, "messageType" to operation,
            "requestId" to UUID.randomUUID().toString(), "taskId" to UUID.randomUUID().toString(),
            "sessionId" to UUID.randomUUID().toString(), "ownerId" to UUID.randomUUID().toString(),
            "deviceBinding" to mapOf("deviceSerial" to "offline-test", "companionPackage" to "com.codexedge.glow",
                "companionVersionCode" to 18, "signerCertificateSha256" to "a".repeat(64)),
            "expiresAtEpochMs" to now + 60000,
            "payload" to mapOf("expectedPackage" to "org.example.app", "expectedWindowId" to 1,
                "maxAgeMs" to 2500, "maxNodes" to 4096)))
    }
    private fun intent(json: String) = Intent(FastPathV1_1Receiver.ACTION).putExtra(
        FastPathV1_1Receiver.REQUEST_EXTRA,
        Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

    @Test fun protectedReceiverBoundaryRoutesV11ToTypedCaptureResponse() {
        val receiver = FastPathV1_1Receiver()
        val result = receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(envelope()))
        assertEquals(Activity.RESULT_OK, result.first)
        val response = String(Base64.decode(result.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        assertTrue(response.contains("evidence.capture.result"))
        assertTrue(response.contains("OBSERVER_UNAVAILABLE"))
        assertFalse(response.contains("UNIMPLEMENTED"))
        assertFalse(response.contains("\"snapshot\":{"))
    }

    @Test fun receiverReturnsCompleteCorrelatedObserverSchemaThreeEvidence() {
        val receiver = FastPathV1_1Receiver()
        val ports = object : com.codexedge.glow.fastpathv1.V1_1RuntimePorts {
            override val executionGateApproved = false
            override val authorityAvailable = false
            override val observerApproved = false
            override fun capture(request: Map<String, Any?>): com.codexedge.glow.fastpathv1.CaptureResult {
                val payload = request["payload"] as Map<*, *>
                val binding = request["deviceBinding"] as Map<*, *>
                val snapshotId = "00000000-0000-4000-8000-000000000010"
                val snapshot = mapOf<String, Any?>("schema" to 3L, "snapshotId" to snapshotId,
                    "packageName" to payload["expectedPackage"], "source" to "accessibility",
                    "windowId" to payload["expectedWindowId"], "windowIdBefore" to payload["expectedWindowId"],
                    "windowIdAfter" to payload["expectedWindowId"], "capturedAtEpochMs" to System.currentTimeMillis(),
                    "capturedAtElapsedRealtimeMs" to 10L, "ageMs" to 1L, "treeComplete" to true, "truncated" to false,
                    "nodes" to listOf(mapOf("windowId" to 1L, "parentIndex" to -1L, "depth" to 0L,
                        "enabled" to true, "clickable" to false, "visible" to true, "focusable" to false,
                        "selected" to false, "checked" to false, "password" to false, "bounds" to listOf(0L, 0L, 10L, 10L))),
                    "traversal" to mapOf("rootCount" to 1L, "visitedNodeCount" to 1L, "maxDepth" to 64L,
                        "nodeTraversalLimit" to 4096L, "timeBudgetMs" to 250L, "durationMs" to 1L,
                        "timeBudgetExceeded" to false, "truncationReason" to null, "traversalError" to null,
                        "collectionSuccess" to true, "windowStable" to true, "windowIdBefore" to 1L, "windowIdAfter" to 1L),
                    "sha256" to "a".repeat(64))
                val producer = mapOf<String, Any?>("producerId" to "org.tessera.observer.ReadOnlyAccessibilityObserverService",
                    "producerPackage" to "org.tessera.observer", "producerUid" to 10566L,
                    "producerVersionName" to "0.3.0-schema3-evidence", "producerVersionCode" to 3L,
                    "signerCertificateSha256" to com.codexedge.glow.fastpathv1.ObserverIpcClientV1.EXPECTED_SIGNER_SHA256,
                    "deviceSerial" to binding["deviceSerial"], "taskId" to request["taskId"], "sessionId" to request["sessionId"],
                    "snapshotId" to snapshotId, "sourceCommit" to "d".repeat(40), "artifactSha256" to "c".repeat(64))
                return com.codexedge.glow.fastpathv1.CaptureResult("CAPTURED", "capture-complete", snapshot, producer)
            }
        }
        val result = receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(envelope()), ports)
        assertEquals(Activity.RESULT_OK, result.first)
        val response = Gson().fromJson(String(Base64.decode(result.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)), Map::class.java)
        assertEquals("ACCEPTED", response["status"])
        val payload = response["payload"] as Map<*, *>
        assertEquals("CAPTURED", payload["captureOutcome"])
        assertEquals(3.0, (payload["snapshot"] as Map<*, *>)["schema"])
        assertEquals("org.tessera.observer", (payload["producer"] as Map<*, *>)["producerPackage"])
    }

    @Test fun v1AndUnknownProfileNeverReachV1HandlerOrRuntime() {
        val receiver = FastPathV1_1Receiver()
        assertEquals(Activity.RESULT_CANCELED, receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(envelope("tessera.fast-path/1"))).first)
        assertEquals(Activity.RESULT_CANCELED, receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(envelope("tessera.fast-path/99"))).first)
    }

    @Test fun wrongActionAndUnexpectedIntentFieldsAreRejected() {
        val receiver = FastPathV1_1Receiver()
        assertEquals(Activity.RESULT_CANCELED, receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), Intent("other")).first)
        val bad = intent(envelope()).putExtra("unexpected", "value")
        assertEquals(Activity.RESULT_CANCELED, receiver.handleProtectedIntent(RuntimeEnvironment.getApplication(), bad).first)
    }
}
