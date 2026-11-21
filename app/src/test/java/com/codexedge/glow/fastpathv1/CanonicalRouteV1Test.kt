package com.codexedge.glow.fastpathv1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalRouteV1Test {
    @Test fun keyOrderDoesNotChangeDigest() {
        val a = mapOf("z" to 1, "a" to listOf("x", "y"))
        val b = linkedMapOf<String, Any?>("a" to listOf("x", "y"), "z" to 1)
        assertEquals(CanonicalRouteV1.fingerprint("route-1", 1, a),
            CanonicalRouteV1.fingerprint("route-1", 1, b))
    }

    @Test fun arrayOrderChangesDigest() {
        assertNotEquals(
            CanonicalRouteV1.fingerprint("route-1", 1, mapOf("steps" to listOf(1, 2))),
            CanonicalRouteV1.fingerprint("route-1", 1, mapOf("steps" to listOf(2, 1))))
    }

    @Test fun rejectsNonIntegerNumbers() {
        assertThrows(IllegalStateException::class.java) { CanonicalRouteV1.encode(1.5) }
    }

    @Test fun normalizesUnicodeAndRejectsDuplicateNormalizedKeys() {
        assertEquals(CanonicalRouteV1.encode("e\u0301"), CanonicalRouteV1.encode("\u00e9"))
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalRouteV1.encode(mapOf("e\u0301" to 1, "\u00e9" to 2))
        }
    }

    @Test fun rejectsUnpairedSurrogate() {
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalRouteV1.encode("\uD800")
        }
    }
}
