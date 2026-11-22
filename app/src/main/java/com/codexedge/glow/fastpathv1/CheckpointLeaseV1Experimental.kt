package com.codexedge.glow.fastpathv1

import java.security.PublicKey
import java.security.Signature

/** Experimental contracts only. A local database is never treated as a monotonic authority. */
internal data class RegistryCheckpointV1(
    val schema: String = "tessera.registry-checkpoint/1",
    val deviceEnrollmentId: String,
    val protocolProfile: String,
    val authorityId: String,
    val keysetVersion: Long,
    val highestCommittedRegistryEpoch: Long,
    val currentCheckpointDigest: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val revoked: Boolean,
    val recoveryState: String,
    val signature: String
)

internal data class OfflineLeaseV1(
    val schema: String = "tessera.offline-lease/1",
    val leaseId: String,
    val deviceEnrollmentId: String,
    val protocolProfile: String,
    val authorityId: String,
    val keysetVersion: Long,
    val registryEpoch: Long,
    val checkpointDigest: String,
    val authorizedRouteFingerprints: List<String>,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val maxValidityMs: Long,
    val bootId: String,
    val issuedElapsedRealtimeMs: Long,
    val revoked: Boolean,
    val signature: String
)

internal interface ExternalCheckpointAuthorityV1 {
    fun latest(deviceEnrollmentId: String, protocolProfile: String): RegistryCheckpointV1?
}

internal interface LeaseReplayStoreV1 {
    /** True only for storage independently shown rollback-resistant for this device. */
    val rollbackResistant: Boolean
    fun consumeOnce(leaseId: String): Boolean
}

internal object CheckpointLeaseV1Experimental {
    const val MAX_LEASE_DURATION_MS = 15 * 60 * 1000L
    const val APPROVAL_STATE = "EXPERIMENTAL_NOT_APPROVED"

    fun verifySignature(publicKey: PublicKey, record: Map<String, Any?>): Boolean = try {
        val signature = record["signature"] as? String ?: return false
        val unsigned = record.toMutableMap().apply { remove("signature") }
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(CanonicalRouteV1.encode(unsigned).toByteArray(Charsets.UTF_8))
        verifier.verify(java.util.Base64.getUrlDecoder().decode(signature))
    } catch (_: Exception) { false }

    fun authorizeOffline(
        lease: OfflineLeaseV1?, checkpoint: RegistryCheckpointV1?, expectedDeviceId: String,
        expectedProtocol: String, routeFingerprint: String, nowEpochMs: Long,
        nowElapsedRealtimeMs: Long, bootId: String, replayStore: LeaseReplayStoreV1?,
        signatureValid: Boolean
    ): String {
        if (lease == null || checkpoint == null || !signatureValid) return "signature-invalid"
        if (lease.schema != "tessera.offline-lease/1" || checkpoint.schema != "tessera.registry-checkpoint/1") return "schema-invalid"
        if (lease.revoked || checkpoint.revoked || checkpoint.recoveryState != "NORMAL") return "authority-unavailable"
        if (lease.deviceEnrollmentId != expectedDeviceId || checkpoint.deviceEnrollmentId != expectedDeviceId) return "device-binding-mismatch"
        if (lease.protocolProfile != expectedProtocol || checkpoint.protocolProfile != expectedProtocol) return "protocol-profile-mismatch"
        if (lease.authorityId != checkpoint.authorityId || lease.keysetVersion != checkpoint.keysetVersion ||
            lease.registryEpoch != checkpoint.highestCommittedRegistryEpoch || lease.checkpointDigest != checkpoint.currentCheckpointDigest) return "checkpoint-mismatch"
        if (nowEpochMs < checkpoint.issuedAtEpochMs || nowEpochMs >= checkpoint.expiresAtEpochMs ||
            lease.issuedAtEpochMs < checkpoint.issuedAtEpochMs || lease.expiresAtEpochMs > checkpoint.expiresAtEpochMs) return "checkpoint-stale"
        if (lease.expiresAtEpochMs <= lease.issuedAtEpochMs || lease.expiresAtEpochMs - lease.issuedAtEpochMs > MAX_LEASE_DURATION_MS ||
            lease.maxValidityMs != MAX_LEASE_DURATION_MS) return "lease-duration-invalid"
        if (nowEpochMs < lease.issuedAtEpochMs || nowEpochMs >= lease.expiresAtEpochMs) return "lease-expired"
        if (lease.bootId != bootId || nowElapsedRealtimeMs < lease.issuedElapsedRealtimeMs ||
            nowElapsedRealtimeMs - lease.issuedElapsedRealtimeMs >= lease.expiresAtEpochMs - lease.issuedAtEpochMs) return "timebase-invalid"
        if (routeFingerprint !in lease.authorizedRouteFingerprints) return "route-scope-mismatch"
        if (replayStore == null || !replayStore.rollbackResistant) return "replay-store-untrusted"
        return if (replayStore.consumeOnce(lease.leaseId)) "AUTHORIZED_OFFLINE" else "lease-replayed"
    }
}
