package com.codexedge.glow.fastpathv1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64

class AuthoritySignatureV1Test {
    private val params = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)

    @Test fun signedRevocationVerifiesOnlyWithPinnedAuthority() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072)
        val pair = generator.generateKeyPair()
        val hash = "a".repeat(64)
        val unsigned = linkedMapOf<String, Any?>(
            "authorityKeyId" to "test-root",
            "registryEpoch" to 2L,
            "issuedAtEpochMs" to 1790000000000L,
            "routeId" to "benchmark.open_guide",
            "routeVersion" to 1L,
            "routeFingerprint" to hash,
            "reasonCode" to "retired")
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.setParameter(params)
        signer.initSign(pair.private)
        signer.update(CanonicalRouteV1.encode(unsigned).toByteArray(Charsets.UTF_8))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
        val record = unsigned.toMutableMap()
        record["signature"] = signature
        assertTrue(AuthoritySignatureV1.verify(record, mapOf("test-root" to pair.public)))
        assertFalse(AuthoritySignatureV1.verify(record, emptyMap()))
        assertFalse(AuthoritySignatureV1.verify(record, mapOf("wrong-root" to pair.public)))
        assertFalse(AuthoritySignatureV1.verify(record + ("reasonCode" to "compromised"),
            mapOf("test-root" to pair.public)))
        assertFalse(AuthoritySignatureV1.verify(record + ("signature" to "A".repeat(80)),
            mapOf("test-root" to pair.public)))
        assertFalse(AuthoritySignatureV1.verify(record + ("extra" to true),
            mapOf("test-root" to pair.public)))
    }

    @Test fun rejectsNon3072BitKeyEvenWhenSignatureValid() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val unsigned = mapOf<String, Any?>(
            "authorityKeyId" to "test-root", "registryEpoch" to 2L,
            "issuedAtEpochMs" to 1L, "routeId" to "benchmark.open_guide",
            "routeVersion" to 1L, "routeFingerprint" to "a".repeat(64),
            "reasonCode" to "retired")
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.setParameter(params)
        signer.initSign(pair.private)
        signer.update(CanonicalRouteV1.encode(unsigned).toByteArray(Charsets.UTF_8))
        val record = unsigned + ("signature" to Base64.getUrlEncoder()
            .withoutPadding().encodeToString(signer.sign()))
        assertFalse(AuthoritySignatureV1.verify(record, mapOf("test-root" to pair.public)))
    }
    @Test fun signedPromotionVerifiesAndRejectsTamperedSignatureBytes() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072)
        val pair = generator.generateKeyPair()
        val hash = "a".repeat(64)
        val route = linkedMapOf<String, Any?>(
            "packageName" to "dev.androidroute.benchmark",
            "appConstraint" to mapOf(
                "versionName" to "1.0", "versionCode" to 1L,
                "signerCertificateSha256" to hash),
            "origin" to mapOf("stateFingerprint" to hash, "requiredMarkers" to listOf("Home")),
            "destination" to mapOf("stateFingerprint" to hash, "requiredMarkers" to listOf("Guide")),
            "windowPolicy" to "same_window",
            "steps" to listOf(mapOf(
                "stepId" to "open-guide",
                "selector" to mapOf("kind" to "text", "value" to "Guide"),
                "uniquenessPolicy" to "exactly_one_actionable_target",
                "expectedBefore" to mapOf("stateFingerprint" to hash, "requiredMarkers" to listOf("Home")),
                "expectedAfter" to mapOf("stateFingerprint" to hash, "requiredMarkers" to listOf("Guide")))))
        val fingerprint = CanonicalRouteV1.fingerprint("benchmark.open_guide", 1L, route)
        val unsigned = linkedMapOf<String, Any?>(
            "authorityKeyId" to "test-root",
            "registryEpoch" to 1L,
            "issuedAtEpochMs" to 1790000000000L,
            "expiresAtEpochMs" to 1790000001000L,
            "routeId" to "benchmark.open_guide",
            "routeVersion" to 1L,
            "routeFingerprint" to fingerprint,
            "routeDefinition" to route)
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.initSign(pair.private)
        signer.setParameter(params)
        signer.update(CanonicalRouteV1.encode(unsigned).toByteArray(Charsets.UTF_8))
        val originalBytes = signer.sign()
        val record = unsigned + ("signature" to Base64.getUrlEncoder()
            .withoutPadding().encodeToString(originalBytes))
        val keys = mapOf("test-root" to pair.public)
        assertTrue(AuthoritySignatureV1.verify(record, keys))
        val changed = originalBytes.copyOf()
        changed[changed.size / 2] = (changed[changed.size / 2].toInt() xor 1).toByte()
        val tampered = record + ("signature" to Base64.getUrlEncoder()
            .withoutPadding().encodeToString(changed))
        assertFalse(AuthoritySignatureV1.verify(tampered, keys))
        assertFalse(AuthoritySignatureV1.verify(
            record + ("routeFingerprint" to "b".repeat(64)), keys))
    }

}
