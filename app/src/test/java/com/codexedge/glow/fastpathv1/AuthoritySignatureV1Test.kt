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
}
