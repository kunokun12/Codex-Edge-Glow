package org.tessera.observer

import java.util.UUID

internal data class ObserverIpcRequestV1(
    val protocol: String, val challenge: String, val requestId: String, val executionId: String,
    val expectedPackage: String, val expectedWindowId: Int, val maxAgeMs: Long, val maxNodes: Int,
    val dispatchElapsedRealtimeMs: Long, val bootTimebaseId: String
)

internal data class ObserverIpcResponseV1(
    val protocol: String, val outcome: String, val reasonCode: String?,
    val requestId: String, val executionId: String, val challenge: String, val bootTimebaseId: String,
    val packageName: String?, val windowId: Int?, val capturedElapsedRealtimeMs: Long?,
    val observerElapsedRealtimeMs: Long, val evidenceDigest: String?, val snapshotJson: String?,
    val observerPackage: String, val observerArtifactIdentity: String, val observerVersionName: String,
    val observerVersionCode: Long, val observerSignerSha256: String, val observerArtifactSha256: String,
    val observerSourceCommit: String, val complete: Boolean,
    val verified: Boolean = false
)

/** Pure IPC policy. Android binding and Accessibility collection stay in their adapters. */
internal class ObserverIpcPolicy(private val capacity: Int = 4096) {
    private val consumed = LinkedHashSet<String>()

    @Synchronized fun consume(challenge: String): Boolean =
        Regex("[A-Za-z0-9_-]{32,128}").matches(challenge) && consumed.size < capacity && consumed.add(challenge)

    fun respond(request: ObserverIpcRequestV1, callerUid: Int, callerPackages: Set<String>,
                snapshot: ObserverSnapshotResult, nowElapsedMs: Long, currentBootId: String,
                artifact: ObserverArtifactV1): ObserverIpcResponseV1 {
        fun refused(code: String) = ObserverIpcResponseV1(
            protocol = PROTOCOL, outcome = "REFUSED", reasonCode = code,
            requestId = request.requestId, executionId = request.executionId, challenge = request.challenge,
            bootTimebaseId = request.bootTimebaseId, packageName = null, windowId = null,
            capturedElapsedRealtimeMs = null, observerElapsedRealtimeMs = nowElapsedMs,
            evidenceDigest = null, snapshotJson = null, observerPackage = OBSERVER_PACKAGE,
            observerArtifactIdentity = artifact.identity, observerVersionName = artifact.versionName,
            observerVersionCode = artifact.versionCode, observerSignerSha256 = artifact.signerSha256,
            observerArtifactSha256 = artifact.apkSha256, observerSourceCommit = artifact.sourceCommit, complete = false)

        if (callerUid <= 0 || callerPackages != setOf(COMPANION_PACKAGE)) return refused("ipc-caller-invalid")
        if (request.protocol != PROTOCOL) return refused("ipc-protocol-invalid")
        if (!UUID_REGEX.matches(request.requestId) || !UUID_REGEX.matches(request.executionId)) return refused("ipc-correlation-invalid")
        if (!CHALLENGE_REGEX.matches(request.challenge)) return refused("ipc-challenge-invalid")
        if (!PACKAGE_REGEX.matches(request.expectedPackage) || request.expectedWindowId < 0 ||
            request.maxAgeMs !in 1..MAX_AGE_MS || request.maxNodes !in 1..ObserverSnapshotV3.MAX_NODES ||
            request.dispatchElapsedRealtimeMs < 0) return refused("ipc-boundary-invalid")
        if (!BOOT_ID_REGEX.matches(currentBootId) || request.bootTimebaseId != currentBootId)
            return refused("ipc-timebase-mismatch")
        if (!consume(request.challenge)) return refused("ipc-challenge-replayed")

        return when (snapshot) {
            is ObserverSnapshotResult.Refused -> refused(snapshot.reasonCode)
            is ObserverSnapshotResult.Captured -> {
                val value = snapshot.snapshot
                when {
                    !value.complete || value.nodes.isEmpty() || value.nodes.size > request.maxNodes -> refused("snapshot-incomplete")
                    value.packageName != request.expectedPackage -> refused("snapshot-package-mismatch")
                    value.windowId != request.expectedWindowId -> refused("snapshot-window-mismatch")
                    value.capturedAtElapsedRealtimeMs <= request.dispatchElapsedRealtimeMs -> refused("snapshot-pre-dispatch")
                    nowElapsedMs < value.capturedAtElapsedRealtimeMs ||
                        nowElapsedMs - value.capturedAtElapsedRealtimeMs > request.maxAgeMs -> refused("snapshot-stale")
                    !DIGEST_REGEX.matches(value.digest) -> refused("snapshot-digest-invalid")
                    value.json.isBlank() || value.json.toByteArray(Charsets.UTF_8).size > MAX_SNAPSHOT_BYTES -> refused("snapshot-payload-too-large")
                    else -> ObserverIpcResponseV1(
                        protocol = PROTOCOL, outcome = "CANDIDATE_EVIDENCE", reasonCode = null,
                        requestId = request.requestId, executionId = request.executionId, challenge = request.challenge,
                        bootTimebaseId = request.bootTimebaseId, packageName = value.packageName,
                        windowId = value.windowId, capturedElapsedRealtimeMs = value.capturedAtElapsedRealtimeMs,
                        observerElapsedRealtimeMs = nowElapsedMs, evidenceDigest = value.digest, snapshotJson = value.json,
                        observerPackage = OBSERVER_PACKAGE, observerArtifactIdentity = artifact.identity,
                        observerVersionName = artifact.versionName, observerVersionCode = artifact.versionCode,
                        observerSignerSha256 = artifact.signerSha256, observerArtifactSha256 = artifact.apkSha256,
                        observerSourceCommit = artifact.sourceCommit,
                        complete = true)
                }
            }
        }
    }

    companion object {
        const val PROTOCOL = "tessera.observer-ipc/1"
        const val OBSERVER_PACKAGE = "org.tessera.observer"
        private const val COMPANION_PACKAGE = "com.codexedge.glow"
        private const val MAX_AGE_MS = 2500L
        private const val MAX_SNAPSHOT_BYTES = 256 * 1024
        private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
        private val UUID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val CHALLENGE_REGEX = Regex("[A-Za-z0-9_-]{32,128}")
        private val BOOT_ID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val DIGEST_REGEX = Regex("[0-9a-f]{64}")
    }
}

internal data class ObserverArtifactV1(
    val identity: String, val versionName: String, val versionCode: Long,
    val signerSha256: String, val apkSha256: String, val sourceCommit: String
)
