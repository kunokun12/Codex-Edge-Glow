package com.codexedge.glow.fastpathv1

import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ObserverIpcClientV1Test {
    private val requestId = "00000000-0000-4000-8000-000000000001"
    private val executionId = "00000000-0000-4000-8000-000000000002"

    @Test fun requestResponseSuccessPathAuthenticatesCandidateWithoutCallingItVerified() {
        var exchanged = false
        val transport = ObserverIpcTransportV1 { request, _ ->
            exchanged = true
            ObserverIpcTransportResultV1.Reply(ObserverIpcTransportReplyV1(
                ObserverIpcReplyV1("tessera.observer-ipc/1", "CANDIDATE_EVIDENCE", null,
                    request.requestId, request.executionId, request.challenge, request.bootTimebaseId,
                    request.expectedPackage, request.expectedWindowId, 2L,
                    10L, true, "a".repeat(64),
                    "org.tessera.observer", "org.tessera.observer/2/" + "b".repeat(64), false),
                10566, setOf("org.tessera.observer")))
        }
        val client = ObserverIpcClientV1(RuntimeEnvironment.getApplication(), transport, { 10L })
        val result = client.capture(requestId, executionId, "org.example.app", 7, dispatchElapsedMs = 0)

        assertTrue(exchanged)
        assertTrue(result is ObserverIpcClientV1.Result.Candidate)
        val evidence = (result as ObserverIpcClientV1.Result.Candidate).evidence
        assertEquals(10566, evidence.authenticatedObserverUid)
        assertEquals("a".repeat(64), evidence.digest)
        assertEquals("UNSUPPORTED_PROFILE", AndroidV1_1RuntimePorts.refusalFor("unsupported-evidence-profile").outcome)
    }

    @Test fun correlatedSnapshotMismatchRemainsSpecificAtRuntimeBoundary() {
        val transport = ObserverIpcTransportV1 { request, _ ->
            ObserverIpcTransportResultV1.Reply(ObserverIpcTransportReplyV1(
                ObserverIpcReplyV1("tessera.observer-ipc/1", "REFUSED", "snapshot-package-mismatch",
                    request.requestId, request.executionId, request.challenge, request.bootTimebaseId,
                    null, null, null, SystemClock.elapsedRealtime(), false, null,
                    "org.tessera.observer", "org.tessera.observer/2/" + "b".repeat(64), false),
                10566, setOf("org.tessera.observer")))
        }
        val result = ObserverIpcClientV1(RuntimeEnvironment.getApplication(), transport, { 10L })
            .capture(requestId, executionId, "org.example.app", 7, 0)

        assertEquals(ObserverIpcClientV1.Result.Refused("snapshot-package-mismatch"), result)
        val capture = AndroidV1_1RuntimePorts.refusalFor((result as ObserverIpcClientV1.Result.Refused).reason)
        assertEquals("OBSERVER_UNAVAILABLE", capture.outcome)
        assertEquals("snapshot-package-mismatch", capture.reason)
    }

    @Test fun permissionTimeoutAndBindingFailuresKeepDistinctReasonCodes() {
        val reasons = listOf("observer-permission-denied", "observer-reply-timeout", "observer-bind-rejected")
        reasons.forEach { reason ->
            val client = ObserverIpcClientV1(RuntimeEnvironment.getApplication(),
                ObserverIpcTransportV1 { _, _ -> ObserverIpcTransportResultV1.Failure(reason) })
            val result = client.capture(requestId, executionId, "org.example.app", 7, 0)
            assertEquals(ObserverIpcClientV1.Result.Refused(reason), result)
            val capture = AndroidV1_1RuntimePorts.refusalFor(reason)
            assertEquals(reason, capture.reason)
        }
        assertEquals("PERMISSION_UNAVAILABLE", AndroidV1_1RuntimePorts.refusalFor("observer-permission-denied").outcome)
        assertEquals("TIMEOUT", AndroidV1_1RuntimePorts.refusalFor("observer-reply-timeout").outcome)
    }
}
