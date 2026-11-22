package com.codexedge.glow.fastpathv1

import org.junit.Assert.assertEquals
import org.junit.Test
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.security.MessageDigest

/** Expected SHA-256 values copied from M1 canonical-route-vectors.json.
 * The JVM suite is still not a full shared-fixture loader.
 */
class CanonicalRouteVectorsTest {
    private fun toKotlin(value: JsonElement): Any? = when {
        value.isJsonNull -> null
        value.isJsonObject -> value.asJsonObject.entrySet().associate { it.key to toKotlin(it.value) }
        value.isJsonArray -> value.asJsonArray.map(::toKotlin)
        value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.asJsonPrimitive.isNumber -> value.asLong
        else -> value.asString
    }

    @Test fun sharedM1VectorFixtureMatchesCanonicalUtf8AndSha256() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fastpath-v1/canonical-route-vectors.json"))
        val fixture = stream.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
        assertEquals("tessera.route-canonical/1", fixture.get("vectorVersion").asString)
        fixture.getAsJsonArray("vectors").forEach { vectorElement ->
            val vector = vectorElement.asJsonObject
            @Suppress("UNCHECKED_CAST")
            val definition = toKotlin(vector.get("routeDefinition")) as Map<String, Any?>
            val routeId = vector.get("routeId").asString
            val routeVersion = vector.get("routeVersion").asLong
            val actual = CanonicalRouteV1.encode(mapOf(
                "fingerprintVersion" to "tessera.route-canonical/1",
                "routeId" to routeId,
                "routeVersion" to routeVersion,
                "routeDefinition" to definition))
            assertEquals(vector.get("name").asString, vector.get("canonicalUtf8").asString, actual)
            val digest = MessageDigest.getInstance("SHA-256").digest(actual.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(vector.get("name").asString, vector.get("sha256").asString, digest)
        }
    }

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
