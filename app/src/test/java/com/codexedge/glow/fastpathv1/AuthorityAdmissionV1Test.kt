package com.codexedge.glow.fastpathv1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64

class AuthorityAdmissionV1Test {
    private val now = 1_790_000_000_000L

    private fun signedRecord(
        epoch: Long = 4L,
        issued: Long = now - 1000L,
        expires: Long = now + 1000L
    ): Pair<Map<String, Any?>, Map<String, java.security.PublicKey>> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072)
        val pair = generator.generateKeyPair()
        val unsigned = linkedMapOf<String, Any?>(
            "authorityKeyId" to "root",
            "registryEpoch" to epoch,
            "issuedAtEpochMs" to issued,
            "expiresAtEpochMs" to expires,
            "routeId" to "benchmark.open_guide",
            "routeVersion" to 1L,
            "routeFingerprint" to "a".repeat(64),
            "routeDefinition" to emptyMap<String, Any?>())
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.initSign(pair.private)
        signer.setParameter(PSSParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        signer.update(CanonicalRouteV1.encode(unsigned).toByteArray(Charsets.UTF_8))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
        return (unsigned + ("signature" to signature)) to mapOf("root" to pair.public)
    }

    @Test fun promotionChecksEpochAndTimeBeforeSignature() {
        val (valid, keys) = signedRecord()
        fun admit(record: Map<String, Any?>, epoch: Long = 3L) =
            AuthorityAdmissionV1.authorize(record, AuthorityAdmissionV1.Kind.PROMOTION,
                now, epoch, keys)
        assertTrue(admit(valid))
        assertFalse(admit(valid, 4L))
        assertFalse(admit(valid, 5L))
        assertFalse(admit(valid + ("issuedAtEpochMs" to now + 1L)))
        assertFalse(admit(valid + ("expiresAtEpochMs" to now)))
        assertFalse(admit(valid + ("expiresAtEpochMs" to now + 86_400_001L)))
        assertFalse(AuthorityAdmissionV1.authorize(
            valid, AuthorityAdmissionV1.Kind.PROMOTION, now, 3L, emptyMap()))
        assertFalse(admit(valid + ("registryEpoch" to 5L)))
        assertFalse(admit(valid + ("unexpected" to true)))
    }

    @Test fun rejectsWrongRecordKind() {
        val (valid, keys) = signedRecord()
        assertFalse(AuthorityAdmissionV1.authorize(
            valid, AuthorityAdmissionV1.Kind.REVOCATION, now, 3L, keys))
    }
}
