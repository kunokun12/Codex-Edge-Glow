package com.codexedge.glow.fastpathv1

internal data class ObserverIpcExpectedV1(
    val requestId: String, val executionId: String, val challenge: String,
    val bootTimebaseId: String, val packageName: String, val windowId: Int, val dispatchElapsedMs: Long,
    val maxAgeMs: Long, val maxNodes: Int,
    val expectedObserverUid: Int, val expectedVersionCode: Long, val expectedVersionName: String,
    val expectedSignerSha256: String, val expectedArtifactSha256: String
)
internal data class ObserverIpcReplyV1(
    val protocol: String, val outcome: String, val reasonCode: String?,
    val requestId: String, val executionId: String, val challenge: String, val bootTimebaseId: String,
    val packageName: String?, val windowId: Int?, val capturedElapsedMs: Long?,
    val observerElapsedMs: Long, val complete: Boolean, val digest: String?,
    val snapshotJson: String?, val observerPackage: String, val artifactIdentity: String,
    val observerVersionName: String, val observerVersionCode: Long, val observerSignerSha256: String,
    val observerArtifactSha256: String, val observerSourceCommit: String, val verified: Boolean
)
internal data class AuthenticatedObserverEvidenceV1(
    val requestId: String, val executionId: String, val packageName: String, val windowId: Int,
    val capturedElapsedMs: Long, val digest: String, val bootTimebaseId: String,
    val artifactIdentity: String, val observerUid: Int, val snapshot: Map<String, Any?>,
    val observerVersionName: String, val observerVersionCode: Long, val observerSignerSha256: String,
    val observerArtifactSha256: String, val observerSourceCommit: String
)
internal sealed interface ObserverIpcValidatedResponseV1 {
    data class Candidate(val evidence: AuthenticatedObserverEvidenceV1) : ObserverIpcValidatedResponseV1
    data class Refused(val reasonCode: String) : ObserverIpcValidatedResponseV1
}

/** Validates identity/correlation before distinguishing candidate evidence from a precise refusal. */
internal object ObserverIpcReplyValidatorV1 {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val bootId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val digest = Regex("[0-9a-f]{64}")
    private val reason = Regex("[a-z][a-z0-9-]{0,63}")

    fun validate(expected: ObserverIpcExpectedV1, reply: ObserverIpcReplyV1, callerUid: Int,
                 callerPackages: Set<String>, nowElapsedMs: Long, maxAgeMs: Long = 2500): ObserverIpcValidatedResponseV1 {
        require(callerUid > 0 && callerPackages == setOf("org.tessera.observer")) { "observer-ipc-identity-mismatch" }
        require(expected.requestId.matches(uuid) && expected.executionId.matches(uuid)) { "observer-ipc-request-invalid" }
        require(expected.challenge.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "observer-ipc-challenge-invalid" }
        require(expected.bootTimebaseId.matches(bootId)) { "observer-ipc-timebase-invalid" }
        require(reply.protocol == "tessera.observer-ipc/1") { "observer-ipc-protocol-invalid" }
        require(reply.requestId == expected.requestId && reply.executionId == expected.executionId &&
            reply.challenge == expected.challenge) { "observer-ipc-correlation-mismatch" }
        require(reply.bootTimebaseId == expected.bootTimebaseId) { "observer-ipc-timebase-mismatch" }
        require(reply.observerPackage == "org.tessera.observer") { "observer-ipc-package-mismatch" }
        require(reply.artifactIdentity.matches(Regex("org\\.tessera\\.observer/[1-9][0-9]*/[0-9a-f]{64}"))) {
            "observer-ipc-artifact-invalid"
        }
        require(callerUid == expected.expectedObserverUid && reply.observerVersionCode == expected.expectedVersionCode &&
            reply.observerVersionName == expected.expectedVersionName && reply.observerSignerSha256 == expected.expectedSignerSha256 &&
            reply.observerArtifactSha256 == expected.expectedArtifactSha256 &&
            reply.artifactIdentity == "org.tessera.observer/${expected.expectedVersionCode}/${expected.expectedSignerSha256}") {
            "observer-ipc-artifact-mismatch"
        }
        require(reply.observerSourceCommit.matches(Regex("[0-9a-f]{40}"))) { "observer-ipc-source-provenance-invalid" }
        require(!reply.verified) { "observer-ipc-forged-verification" }

        if (reply.outcome == "REFUSED") {
            val code = reply.reasonCode ?: error("observer-ipc-refusal-code-missing")
            require(reason.matches(code)) { "observer-ipc-refusal-code-invalid" }
            require(reply.packageName == null && reply.windowId == null && reply.capturedElapsedMs == null &&
                reply.digest == null && reply.snapshotJson == null && !reply.complete) { "observer-ipc-refusal-carried-evidence" }
            return ObserverIpcValidatedResponseV1.Refused(code)
        }

        require(reply.outcome == "CANDIDATE_EVIDENCE" && reply.reasonCode == null) { "observer-ipc-outcome-invalid" }
        val packageName = reply.packageName ?: error("observer-ipc-package-missing")
        val windowId = reply.windowId ?: error("observer-ipc-window-missing")
        val captured = reply.capturedElapsedMs ?: error("observer-ipc-capture-time-missing")
        val evidenceDigest = reply.digest ?: error("observer-ipc-digest-missing")
        val snapshotJson = reply.snapshotJson ?: error("observer-ipc-snapshot-missing")
        require(packageName == expected.packageName && windowId == expected.windowId) { "observer-ipc-window-mismatch" }
        require(expected.dispatchElapsedMs >= 0 && captured > expected.dispatchElapsedMs &&
            nowElapsedMs >= captured && nowElapsedMs - captured <= maxAgeMs && reply.observerElapsedMs >= captured) {
            "observer-ipc-stale-or-pre-dispatch"
        }
        require(reply.complete) { "observer-ipc-incomplete" }
        require(evidenceDigest.matches(digest)) { "observer-ipc-digest-invalid" }
        val snapshot = ObserverSnapshotV3Codec.parseAndValidate(snapshotJson, evidenceDigest, packageName, windowId,
            captured, nowElapsedMs, expected.maxAgeMs, expected.maxNodes)
        return ObserverIpcValidatedResponseV1.Candidate(AuthenticatedObserverEvidenceV1(expected.requestId,
            expected.executionId, packageName, windowId, captured, evidenceDigest, expected.bootTimebaseId,
            reply.artifactIdentity, callerUid, snapshot, reply.observerVersionName, reply.observerVersionCode,
            reply.observerSignerSha256, reply.observerArtifactSha256, reply.observerSourceCommit))
    }
}
