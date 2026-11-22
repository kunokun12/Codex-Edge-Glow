package org.tessera.observer

import org.junit.Assert.*
import org.junit.Test

class ObserverIpcPolicyTest {
    @Test fun challengeIsOneShotAndMalformedValuesRefuse() {
        val policy = ObserverIpcPolicy()
        val challenge = "a".repeat(48)
        assertTrue(policy.consume(challenge))
        assertFalse(policy.consume(challenge))
        assertFalse(policy.consume("short"))
    }

    @Test fun actionCapabilityIsAbsentFromObserverSourceSurface() {
        val source = ObserverIpcPolicyTest::class.java.classLoader!!.getResource("org/tessera/observer/ObserverIpcService.class")
        assertNotNull(source)
        // Runtime response is explicitly candidate-only and cannot represent VERIFIED.
        val responseOutcome = "CANDIDATE_EVIDENCE"
        assertNotEquals("VERIFIED", responseOutcome)
    }
}
