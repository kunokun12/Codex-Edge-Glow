package org.tessera.observer

import java.util.UUID

internal data class ObserverIpcRequestV1(
    val protocol: String,
    val challenge: String,
    val requestId: String,
    val executionId: String,
    val expectedPackage: String,
    val expectedWindowId: Int,
    val dispatchElapsedRealtimeMs: Long,
    val bootTimebaseId: String
)

internal data class ObserverIpcResponseV1(
    val protocol: String,
    val outcome: String,
    val reasonCode: String?,
    val requestId: String,
    val executionId: String,
    val challenge: String,
    val bootTimebaseId: String,
    val packageName: String?,
    val windowId: Int?,
    val capturedElapsedRealtimeMs: Long?,
    val observerElapsedRealtimeMs: Long,
    val evidenceDigest: String?,
    val observerPackage: String,
    val observerArtifactIdentity: String,
    val complete: Boolean,
    val verified: Boolean = false
)

/** Pure IPC policy. Android binding and Accessibility collection stay in their adapters. */
internal class ObserverIpcPolicy(private val capacity: Int = 4096) {
    private val consumed = LinkedHashSet<String>()

    @Synchronized fun consume(challenge: String): Boolean =
        Regex("[A-Za-z0-9_-]{32,128}").matches(challenge) && consumed.size < capacity && consumed.add(challenge)

    fun respond(request: ObserverIpcRequestV1, callerUid: Int, callerPackages: Set<String>,
                snapshot: ObserverSnapshotResult, nowElapsedMs: Long, currentBootId: String,
                artifactIdentity: String): ObserverIpcResponseV1 {
        fun refused(code: String, candidate: ObserverSnapshot? = null) = ObserverIpcResponseV1(
            protocol = PROTOCOL, outcome = "REFUSED", reasonCode = code,
            requestId = request.requestId, executionId = request.executionId, challenge = request.challenge,
            bootTimebaseId = request.bootTimebaseId, packageName = candidate?.packageName,
            windowId = candidate?.windowId, capturedElapsedRealtimeMs = candidate?.capturedElapsedRealtimeMs,
            observerElapsedRealtimeMs = nowElapsedMs, evidenceDigest = null,
            observerPackage = OBSERVER_PACKAGE, observerArtifactIdentity = artifactIdentity,
            complete = false)

        if (callerUid <= 0 || callerPackages != setOf(COMPANION_PACKAGE)) return refused("ipc-caller-invalid")
        if (request.protocol != PROTOCOL) return refused("ipc-protocol-invalid")
        if (!UUID_REGEX.matches(request.requestId) || !UUID_REGEX.matches(request.executionId)) return refused("ipc-correlation-invalid")
        if (!CHALLENGE_REGEX.matches(request.challenge)) return refused("ipc-challenge-invalid")
        if (request.expectedPackage.isBlank() || request.expectedPackage.length > 255 || request.expectedWindowId < 0 ||
            request.dispatchElapsedRealtimeMs < 0) return refused("ipc-boundary-invalid")
        if (!BOOT_ID_REGEX.matches(currentBootId) || request.bootTimebaseId != currentBootId)
            return refused("ipc-timebase-mismatch")
        if (!consume(request.challenge)) return refused("ipc-challenge-replayed")

        return when (snapshot) {
            is ObserverSnapshotResult.Refused -> refused(snapshot.reasonCode)
            is ObserverSnapshotResult.Captured -> {
                val value = snapshot.snapshot
                when {
                    !value.complete || value.nodes.isEmpty() -> refused("snapshot-incomplete")
                    value.packageName != request.expectedPackage -> refused("snapshot-package-mismatch")
                    value.windowId != request.expectedWindowId -> refused("snapshot-window-mismatch")
                    value.capturedElapsedRealtimeMs <= request.dispatchElapsedRealtimeMs -> refused("snapshot-pre-dispatch")
                    nowElapsedMs < value.capturedElapsedRealtimeMs ||
                        nowElapsedMs - value.capturedElapsedRealtimeMs > MAX_AGE_MS -> refused("snapshot-stale")
                    !DIGEST_REGEX.matches(value.digest) -> refused("snapshot-digest-invalid")
                    else -> ObserverIpcResponseV1(
                        protocol = PROTOCOL, outcome = "CANDIDATE_EVIDENCE", reasonCode = null,
                        requestId = request.requestId, executionId = request.executionId, challenge = request.challenge,
                        bootTimebaseId = request.bootTimebaseId, packageName = value.packageName,
                        windowId = value.windowId, capturedElapsedRealtimeMs = value.capturedElapsedRealtimeMs,
                        observerElapsedRealtimeMs = nowElapsedMs, evidenceDigest = value.digest,
                        observerPackage = OBSERVER_PACKAGE, observerArtifactIdentity = artifactIdentity,
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
        private val UUID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val CHALLENGE_REGEX = Regex("[A-Za-z0-9_-]{32,128}")
        private val BOOT_ID_REGEX = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val DIGEST_REGEX = Regex("[0-9a-f]{64}")
    }
}
