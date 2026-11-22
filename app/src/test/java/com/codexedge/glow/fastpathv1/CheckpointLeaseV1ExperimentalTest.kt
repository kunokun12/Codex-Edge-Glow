package com.codexedge.glow.fastpathv1

import org.junit.Assert.*
import org.junit.Test

class CheckpointLeaseV1ExperimentalTest {
    private val now = 1_800_000_000_000L
    private val checkpoint = RegistryCheckpointV1(deviceEnrollmentId="device-test", protocolProfile="tessera.fast-path/1.1",
        authorityId="mock-authority", keysetVersion=1, highestCommittedRegistryEpoch=9, currentCheckpointDigest="a".repeat(64),
        issuedAtEpochMs=now-1000, expiresAtEpochMs=now+1_200_000, revoked=false, recoveryState="NORMAL", signature="test")
    private val lease = OfflineLeaseV1(leaseId="lease-once", deviceEnrollmentId="device-test", protocolProfile="tessera.fast-path/1.1",
        authorityId="mock-authority", keysetVersion=1, registryEpoch=9, checkpointDigest="a".repeat(64),
        authorizedRouteFingerprints=listOf("b".repeat(64)), issuedAtEpochMs=now-500,
        expiresAtEpochMs=now+CheckpointLeaseV1Experimental.MAX_LEASE_DURATION_MS-500,
        maxValidityMs=CheckpointLeaseV1Experimental.MAX_LEASE_DURATION_MS, bootId="boot-a", issuedElapsedRealtimeMs=3900,
        revoked=false, signature="test")
    private class ReplayStore(override val rollbackResistant: Boolean = true) : LeaseReplayStoreV1 {
        private val used = mutableSetOf<String>()
        override fun consumeOnce(leaseId: String) = used.add(leaseId)
    }
    private fun authorize(l: OfflineLeaseV1? = lease, c: RegistryCheckpointV1? = checkpoint,
        wall: Long = now, elapsed: Long = 4000, boot: String = "boot-a", store: LeaseReplayStoreV1? = ReplayStore()) =
        CheckpointLeaseV1Experimental.authorizeOffline(l,c,"device-test","tessera.fast-path/1.1","b".repeat(64),wall,elapsed,boot,store,true)

    @Test fun offlineLeaseIsScopedAndSingleUse() {
        val store = ReplayStore()
        assertEquals("AUTHORIZED_OFFLINE", authorize(store=store))
        assertEquals("lease-replayed", authorize(store=store))
        assertEquals("route-scope-mismatch", CheckpointLeaseV1Experimental.authorizeOffline(lease,checkpoint,"device-test","tessera.fast-path/1.1","c".repeat(64),now,4000,"boot-a",ReplayStore(),true))
    }

    @Test fun rebootClockManipulationAndCheckpointOutageFailClosed() {
        assertEquals("timebase-invalid", authorize(boot="boot-b"))
        assertEquals("timebase-invalid", authorize(elapsed=1))
        assertEquals("checkpoint-stale", authorize(wall=now-10000))
        assertEquals("signature-invalid", authorize(c=null))
        assertEquals("replay-store-untrusted", authorize(store=ReplayStore(false)))
    }

    @Test fun rollbackRevocationAndExpiryFailClosed() {
        assertEquals("checkpoint-mismatch", authorize(c=checkpoint.copy(highestCommittedRegistryEpoch=8)))
        assertEquals("authority-unavailable", authorize(c=checkpoint.copy(revoked=true)))
        assertEquals("lease-expired", authorize(wall=lease.expiresAtEpochMs))
        assertEquals("lease-duration-invalid", authorize(l=lease.copy(expiresAtEpochMs=lease.issuedAtEpochMs+900001)))
    }
}
