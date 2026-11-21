package com.codexedge.glow.fastpathv1

import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64

/**
 * Pure verification primitive. No trust anchors are installed by this class.
 * Callers MUST supply an APK-pinned authority keyset; incoming messages cannot add keys.
 * This does not authorize promotion/revocation, update epochs, or persist a registry.
 */
internal object AuthoritySignatureV1 {
    private val pss = PSSParameterSpec("SHA-256", "MGF1",
        MGF1ParameterSpec.SHA256, 32, 1)
    private val b64url = Regex("[A-Za-z0-9_-]+")

    fun verify(record: Map<String, Any?>, trustedKeys: Map<String, PublicKey>): Boolean {
        return try {
            val authority = record["authorityKeyId"] as? String ?: return false
            val key = trustedKeys[authority] as? RSAPublicKey ?: return false
            if (key.modulus.bitLength() != 3072) return false
            val encoded = record["signature"] as? String ?: return false
            if (!b64url.matches(encoded)) return false
            val signatureBytes = Base64.getUrlDecoder().decode(encoded)
            if (signatureBytes.size != 384 ||
                Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes) != encoded) return false
            if (record.keys !in listOf(PROMOTION_KEYS, REVOCATION_KEYS)) return false
            val signed = record.filterKeys { it != "signature" }
            val canonical = CanonicalRouteV1.encode(signed).toByteArray(Charsets.UTF_8)
            verifyWithAvailableProvider(key, canonical, signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    // Android API 23+ exposes SHA256withRSA/PSS; desktop JCA often uses RSASSA-PSS.
    private fun verifyWithAvailableProvider(
        key: RSAPublicKey, canonical: ByteArray, signatureBytes: ByteArray
    ): Boolean {
        for (algorithm in listOf("SHA256withRSA/PSS", "RSASSA-PSS")) {
            try {
                val verifier = Signature.getInstance(algorithm)
                verifier.initVerify(key)
                verifier.setParameter(pss)
                verifier.update(canonical)
                return verifier.verify(signatureBytes)
            } catch (_: java.security.NoSuchAlgorithmException) {
                continue
            } catch (_: java.security.InvalidAlgorithmParameterException) {
                continue
            } catch (_: java.security.InvalidKeyException) {
                continue
            }
        }
        return false
    }

    private val PROMOTION_KEYS = setOf("authorityKeyId", "registryEpoch",
        "issuedAtEpochMs", "expiresAtEpochMs", "routeId", "routeVersion",
        "routeFingerprint", "routeDefinition", "signature")
    private val REVOCATION_KEYS = setOf("authorityKeyId", "registryEpoch",
        "issuedAtEpochMs", "routeId", "routeVersion", "routeFingerprint",
        "reasonCode", "signature")
}
