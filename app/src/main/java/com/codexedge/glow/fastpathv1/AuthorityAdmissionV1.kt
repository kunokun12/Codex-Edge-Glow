package com.codexedge.glow.fastpathv1

import java.security.PublicKey

/**
 * Pure signature/time/schema authorization primitive used by the durable registry.
 * The registry must atomically enforce epoch monotonicity with its durable commit;
 * this check alone is not a transaction.
 */
internal object AuthorityAdmissionV1 {
    private const val MAX_PROMOTION_LIFETIME_MS = 86_400_000L

    enum class Kind { PROMOTION, REVOCATION }

    fun authorize(
        record: Map<String, Any?>,
        kind: Kind,
        nowEpochMs: Long,
        committedEpoch: Long,
        trustedKeys: Map<String, PublicKey>
    ): Boolean {
        if (nowEpochMs < 0 || committedEpoch < 0 || trustedKeys.isEmpty()) return false
        val expectedKeys = when (kind) {
            Kind.PROMOTION -> setOf("authorityKeyId", "registryEpoch", "issuedAtEpochMs",
                "expiresAtEpochMs", "routeId", "routeVersion", "routeFingerprint",
                "routeDefinition", "signature")
            Kind.REVOCATION -> setOf("authorityKeyId", "registryEpoch", "issuedAtEpochMs",
                "routeId", "routeVersion", "routeFingerprint", "reasonCode", "signature")
        }
        if (record.keys != expectedKeys) return false
        val epoch = record["registryEpoch"] as? Long ?: return false
        val issued = record["issuedAtEpochMs"] as? Long ?: return false
        if (epoch <= committedEpoch || epoch <= 0 || issued <= 0 || issued > nowEpochMs) return false
        if (kind == Kind.PROMOTION) {
            val expires = record["expiresAtEpochMs"] as? Long ?: return false
            if (expires <= nowEpochMs || expires <= issued ||
                expires - issued > MAX_PROMOTION_LIFETIME_MS) return false
        }
        return AuthoritySignatureV1.verify(record, trustedKeys)
    }
}
