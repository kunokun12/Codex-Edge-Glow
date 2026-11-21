package com.codexedge.glow.fastpathv1

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected SHA-256 values copied from M1 canonical-route-vectors.json.
 * The JVM suite is still not a full shared-fixture loader.
 */
class CanonicalRouteVectorsTest {
    @Test fun nfcVector() {
        val route = linkedMapOf<String, Any?>(
            "packageName" to "example.app",
            "marker" to "Cafe\u0301 🧭",
            "steps" to listOf(mapOf("id" to "one", "selector" to "Résumé"))
        )
        assertEquals("a4d72b0bfac1f5986b0a3b253a525f9c0b2d335883fef91b00007fd46768bec1",
            CanonicalRouteV1.fingerprint("sample.unicode", 2, route))
    }

    @Test fun codepointOrderVector() {
        val route = linkedMapOf<String, Any?>(
            "𐀀" to "supplementary",
            "\uE000" to "bmp"
        )
        assertEquals("e6c43f726a44d9d9366ed797e5a19544fa4dba6d22b6b8ef421ac957154f8f5b",
            CanonicalRouteV1.fingerprint("sample.unicode-keys", 3, route))
    }

    @Test fun benchmarkVector() {
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        val route = mapOf<String, Any?>(
            "packageName" to "dev.androidroute.benchmark",
            "appConstraint" to mapOf(
                "versionName" to "1.0", "versionCode" to 1,
                "signerCertificateSha256" to "a".repeat(64)),
            "origin" to mapOf("stateFingerprint" to b, "requiredMarkers" to listOf("benchmark-home")),
            "destination" to mapOf("stateFingerprint" to c, "requiredMarkers" to listOf("guide-screen")),
            "windowPolicy" to "same_window",
            "steps" to listOf(mapOf(
                "stepId" to "open-guide",
                "selector" to mapOf("kind" to "text", "value" to "Open Guide"),
                "expectedBefore" to mapOf("stateFingerprint" to b, "requiredMarkers" to listOf("benchmark-home")),
                "expectedAfter" to mapOf("stateFingerprint" to c, "requiredMarkers" to listOf("guide-screen")),
                "uniquenessPolicy" to "exactly_one_actionable_target"))
        )
        assertEquals("f44b3325835f4fed73b83977aa44b2b03fe456e602d0871d592c6481f217fb68",
            CanonicalRouteV1.fingerprint("benchmark.open_guide", 1, route))
    }
}
