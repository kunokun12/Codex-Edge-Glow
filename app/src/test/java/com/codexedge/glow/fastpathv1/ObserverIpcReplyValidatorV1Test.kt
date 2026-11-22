package com.codexedge.glow.fastpathv1

import org.junit.Assert.*
import org.junit.Test

class ObserverIpcReplyValidatorV1Test {
    private val request = "00000000-0000-4000-8000-000000000001"
    private val execution = "00000000-0000-4000-8000-000000000002"
    private val challenge = "A".repeat(48)
    private val boot = "12345678-1234-1234-1234-123456789abc"
    private val expected = ObserverIpcExpectedV1(request, execution, challenge, boot, "org.example.app", 9, 1000)
    private val good = ObserverIpcReplyV1("tessera.observer-ipc/1", "CANDIDATE_EVIDENCE", request, execution,
        challenge, boot, "org.example.app", 9, 1100, true, "a".repeat(64), "org.tessera.observer",
        "org.tessera.observer/1/" + "b".repeat(64))

    private fun validate(reply: ObserverIpcReplyV1 = good, uid: Int = 10042,
                         packages: Set<String> = setOf("org.tessera.observer"), now: Long = 1200) =
        ObserverIpcReplyValidatorV1.validate(expected, reply, uid, packages, now)

    @Test fun acceptsOnlyFreshCorrelatedCandidateEvidenceAndKeepsUidFromBinder() {
        val evidence = validate()
        assertEquals(10042, evidence.observerUid)
        assertEquals("a".repeat(64), evidence.digest)
    }

    @Test fun rejectsWrongOrSharedCallerIdentity() {
        assertThrows(IllegalArgumentException::class.java) { validate(uid = 0) }
        assertThrows(IllegalArgumentException::class.java) { validate(packages = setOf("org.tessera.observer", "other.app")) }
    }

    @Test fun rejectsVersionOutcomeAndRequestChallengeReplayMismatch() {
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(protocol = "1")) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(outcome = "VERIFIED")) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(requestId = execution)) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(executionId = request)) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(challenge = "B".repeat(48))) }
    }

    @Test fun rejectsBootPackageAndWindowDrift() {
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(bootTimebaseId = "0".repeat(36))) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(packageName = "org.other")) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(windowId = 10)) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(observerPackage = "com.codexedge.glow")) }
    }

    @Test fun rejectsStalePreDispatchIncompleteAndMalformedEvidence() {
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(capturedElapsedMs = 1000)) }
        assertThrows(IllegalArgumentException::class.java) { validate(now = 5000) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(capturedElapsedMs = 1300), now = 1200) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(complete = false)) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(digest = "bad")) }
        assertThrows(IllegalArgumentException::class.java) { validate(good.copy(artifactIdentity = "untrusted")) }
    }
}
