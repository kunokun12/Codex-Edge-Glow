package com.codexedge.glow

import android.app.Activity
import android.content.Intent
import android.util.Base64
import com.codexedge.glow.fastpathv1.CanonicalRouteV1
import com.codexedge.glow.fastpathv1.CaptureResult
import com.codexedge.glow.fastpathv1.V1_1RuntimePorts
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FastPathV1_1ReceiverSimulationTest {
    private val requestId = "00000000-0000-4000-8000-000000000001"
    private val binding = mapOf("deviceSerial" to "offline-test", "companionPackage" to "com.codexedge.glow",
        "companionVersionCode" to 18, "signerCertificateSha256" to "a".repeat(64))

    private fun requestJson(): String {
        val now = System.currentTimeMillis()
        val origin = mapOf("stateFingerprint" to "b".repeat(64), "requiredMarkers" to listOf("screen.home"))
        val destination = mapOf("stateFingerprint" to "c".repeat(64), "requiredMarkers" to listOf("screen.done"))
        val route = mapOf("packageName" to "org.example.app",
            "appConstraint" to mapOf("versionName" to "1.0", "versionCode" to 1, "signerCertificateSha256" to "d".repeat(64)),
            "origin" to origin, "destination" to destination, "windowPolicy" to "same_window",
            "steps" to listOf(mapOf("stepId" to "open", "selector" to mapOf("kind" to "text", "value" to "Open"),
                "uniquenessPolicy" to "exactly_one_actionable_target", "expectedBefore" to origin, "expectedAfter" to destination)))
        val routeFingerprint = CanonicalRouteV1.fingerprint("route.demo", 1, route)
        return Gson().toJson(mapOf("protocolVersion" to "tessera.fast-path/1.1", "messageType" to "route.execute",
            "requestId" to requestId, "taskId" to "00000000-0000-4000-8000-000000000002",
            "sessionId" to "00000000-0000-4000-8000-000000000003", "ownerId" to "00000000-0000-4000-8000-000000000004",
            "deviceBinding" to binding, "expiresAtEpochMs" to now + 60000,
            "payload" to mapOf("routeId" to "route.demo", "routeVersion" to 1,
                "routeFingerprint" to routeFingerprint, "routeDefinition" to route)))
    }

    private fun intent(json: String) = Intent(FastPathV1_1Receiver.ACTION).putExtra(FastPathV1_1Receiver.REQUEST_EXTRA,
        Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

    private class PipelineFixture(private val stopAt: String? = null) : V1_1RuntimePorts {
        override val executionGateApproved = true
        override val authorityAvailable = true
        override val observerApproved = true
        val calls = mutableListOf<String>()
        var actionAttempts = 0

        override fun capture(request: Map<String, Any?>) = CaptureResult("OBSERVER_UNAVAILABLE", "observer-unavailable")

        override fun execute(request: Map<String, Any?>, nowEpochMs: Long): Map<String, Any?> {
            val pipeline = listOf("registry", "lease-validation", "origin-fingerprint", "exact-selector",
                "execution-fence", "dispatch-possible-commit", "action", "independent-observer", "result-persist")
            for (stage in pipeline) {
                calls += stage
                if (stage == stopAt) return refusal(request, nowEpochMs, "$stage-failed")
                if (stage == "action") actionAttempts++
            }
            return response(request, nowEpochMs)
        }

        private fun refusal(request: Map<String, Any?>, now: Long, reason: String) = base(request, now) + mapOf(
            "status" to "REFUSED", "reasonCode" to reason, "lease" to mapOf("state" to "NONE", "expiresAtEpochMs" to null),
            "execution" to mapOf("currentStepId" to null, "completedStepCount" to 0L, "journalSequence" to 0L,
                "dispatchPossible" to false, "actionReceipt" to null), "snapshot" to null, "verification" to null, "provenance" to null)

        private fun response(request: Map<String, Any?>, now: Long) = base(request, now) + mapOf(
            "status" to "VERIFIED_SUCCESS", "reasonCode" to "destination-verified",
            "lease" to mapOf("state" to "RELEASED", "expiresAtEpochMs" to now + 10000),
            "execution" to mapOf("currentStepId" to "open", "completedStepCount" to 1L, "journalSequence" to 1L,
                "dispatchPossible" to true, "actionReceipt" to "ACCEPTED"), "snapshot" to null,
            "verification" to mapOf("status" to "VERIFIED", "packageName" to "org.example.app", "windowId" to 1,
                "observedAtEpochMs" to now, "ageMs" to 0L, "destinationFingerprint" to "c".repeat(64),
                "independent" to true, "observerId" to "fixture-observer"),
            "provenance" to mapOf("sourceCommit" to "fixture-commit", "artifactSha256" to "e".repeat(64),
                "signerCertificateSha256" to "f".repeat(64), "versionName" to "fixture", "versionCode" to 1L))

        private fun base(request: Map<String, Any?>, now: Long) = mapOf(
            "protocolVersion" to request["protocolVersion"], "messageType" to "route.execute.result",
            "requestId" to request["requestId"], "taskId" to request["taskId"], "sessionId" to request["sessionId"],
            "ownerId" to request["ownerId"], "deviceBinding" to request["deviceBinding"], "observedAtEpochMs" to now)
    }

    @Test fun receiverDispatchRunsInjectedPipelineAndEmitsCorrelatedVerifiedResponse() {
        val fixture = PipelineFixture()
        val result = FastPathV1_1Receiver().handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(requestJson()), fixture)
        assertEquals(Activity.RESULT_OK, result.first)
        val response = JsonParser.parseString(String(Base64.decode(result.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))).asJsonObject
        assertEquals("route.execute.result", response.get("messageType").asString)
        assertEquals("VERIFIED_SUCCESS", response.get("status").asString)
        assertEquals(requestId, response.get("requestId").asString)
        assertEquals(1, fixture.actionAttempts)
        assertEquals(listOf("registry", "lease-validation", "origin-fingerprint", "exact-selector", "execution-fence",
            "dispatch-possible-commit", "action", "independent-observer", "result-persist"), fixture.calls)
    }

    @Test fun wrongOriginStopsBeforeDispatchAndProductionCompositionCannotUseFixture() {
        val fixture = PipelineFixture(stopAt = "origin-fingerprint")
        val result = FastPathV1_1Receiver().handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(requestJson()), fixture)
        assertEquals(Activity.RESULT_OK, result.first)
        assertEquals(0, fixture.actionAttempts)
        assertFalse(fixture.calls.contains("exact-selector"))

        val production = FastPathV1_1Receiver().handleProtectedIntent(RuntimeEnvironment.getApplication(), intent(requestJson()))
        assertEquals(Activity.RESULT_OK, production.first)
        val productionResponse = String(Base64.decode(production.second, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        assertTrue(productionResponse.contains("runtime-approval-required"))
        assertFalse(productionResponse.contains("VERIFIED_SUCCESS"))
    }
}
