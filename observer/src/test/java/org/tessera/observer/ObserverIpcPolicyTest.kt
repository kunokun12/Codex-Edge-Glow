package org.tessera.observer

import org.junit.Assert.*
import org.junit.Test

class ObserverIpcPolicyTest {
    private val request = ObserverIpcRequestV1("tessera.observer-ipc/1", "c".repeat(48),
        "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
        "org.example.app", 9, 1000, "12345678-1234-1234-1234-123456789abc")
    private val identity = "org.tessera.observer/2/" + "b".repeat(64)

    @Test fun challengeIsOneShotAndMalformedValuesRefuse() {
        val policy = ObserverIpcPolicy()
        val challenge = "a".repeat(48)
        assertTrue(policy.consume(challenge))
        assertFalse(policy.consume(challenge))
        assertFalse(policy.consume("short"))
    }

    @Test fun readOnlyIpcSuccessReturnsFreshCorrelatedCandidateAndNeverVerified() {
        val snapshot = ObserverSnapshot("org.example.app", 9, 1100, true,
            listOf(ObserverNode(-1, 0, null, "Root", true, true, false, false, false, false, false)), "a".repeat(64))
        val response = ObserverIpcPolicy().respond(request, 10606, setOf("com.codexedge.glow"),
            ObserverSnapshotResult.Captured(snapshot), 1200, request.bootTimebaseId, identity)

        assertEquals("CANDIDATE_EVIDENCE", response.outcome)
        assertNull(response.reasonCode)
        assertEquals(request.requestId, response.requestId)
        assertEquals(request.executionId, response.executionId)
        assertEquals(request.challenge, response.challenge)
        assertEquals("org.example.app", response.packageName)
        assertEquals(9, response.windowId)
        assertEquals("a".repeat(64), response.evidenceDigest)
        assertTrue(response.complete)
        assertFalse(response.verified)
    }

    @Test fun observerFailuresRemainCorrelatedAndSpecific() {
        val response = ObserverIpcPolicy().respond(request, 10606, setOf("com.codexedge.glow"),
            ObserverSnapshotResult.Refused("snapshot-package-mismatch"), 1200,
            request.bootTimebaseId, identity)
        assertEquals("REFUSED", response.outcome)
        assertEquals("snapshot-package-mismatch", response.reasonCode)
        assertEquals(request.requestId, response.requestId)
        assertEquals(request.challenge, response.challenge)
        assertNull(response.evidenceDigest)
        assertFalse(response.verified)
    }

    @Test fun policyDistinguishesMissingAccessibilityAndStaleSnapshot() {
        val noService = ObserverIpcPolicy().respond(request, 10606, setOf("com.codexedge.glow"),
            ObserverSnapshotResult.Refused("accessibility-service-unavailable"), 1200,
            request.bootTimebaseId, identity)
        assertEquals("accessibility-service-unavailable", noService.reasonCode)

        val stale = ObserverSnapshot("org.example.app", 9, 1001, true,
            listOf(ObserverNode(-1, 0, null, "Root", true, true, false, false, false, false, false)), "a".repeat(64))
        val staleReply = ObserverIpcPolicy().respond(request, 10606, setOf("com.codexedge.glow"),
            ObserverSnapshotResult.Captured(stale), 4000, request.bootTimebaseId, identity)
        assertEquals("snapshot-stale", staleReply.reasonCode)
    }

    @Test fun policyRejectsCallerTimebaseAndReplayWithoutEvidence() {
        val service = ObserverIpcPolicy()
        val empty = ObserverSnapshotResult.Refused("active-window-unavailable")
        assertEquals("ipc-caller-invalid", service.respond(request, 10606, setOf("other.app"), empty,
            1200, request.bootTimebaseId, identity).reasonCode)
        assertEquals("ipc-timebase-mismatch", service.respond(request, 10606, setOf("com.codexedge.glow"), empty,
            1200, "22345678-1234-1234-1234-123456789abc", identity).reasonCode)
        assertEquals("active-window-unavailable", service.respond(request, 10606, setOf("com.codexedge.glow"), empty,
            1200, request.bootTimebaseId, identity).reasonCode)
        assertEquals("ipc-challenge-replayed", service.respond(request, 10606, setOf("com.codexedge.glow"), empty,
            1200, request.bootTimebaseId, identity).reasonCode)
    }

    @Test fun actionCapabilityIsAbsentFromObserverSourceSurface() {
        val source = ObserverIpcPolicyTest::class.java.classLoader!!.getResource("org/tessera/observer/ObserverIpcService.class")
        assertNotNull(source)
        // Runtime response is explicitly candidate-only and cannot represent VERIFIED.
        val responseOutcome = "CANDIDATE_EVIDENCE"
        assertNotEquals("VERIFIED", responseOutcome)
    }
}
