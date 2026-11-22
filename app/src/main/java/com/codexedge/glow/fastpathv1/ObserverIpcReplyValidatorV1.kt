package com.codexedge.glow.fastpathv1

internal data class ObserverIpcExpectedV1(val requestId: String, val executionId: String, val challenge: String,
    val bootTimebaseId: String, val packageName: String, val windowId: Int, val dispatchElapsedMs: Long)
internal data class ObserverIpcReplyV1(val protocol: String, val outcome: String, val requestId: String,
    val executionId: String, val challenge: String, val bootTimebaseId: String, val packageName: String,
    val windowId: Int, val capturedElapsedMs: Long, val complete: Boolean, val digest: String,
    val observerPackage: String, val artifactIdentity: String)
internal data class AuthenticatedObserverEvidenceV1(val requestId: String, val executionId: String,
    val packageName: String, val windowId: Int, val capturedElapsedMs: Long, val digest: String,
    val bootTimebaseId: String, val artifactIdentity: String, val observerUid: Int)

/** Pure policy shared by the Android Messenger client and negative JVM tests. */
internal object ObserverIpcReplyValidatorV1 {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val bootId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val digest = Regex("[0-9a-f]{64}")
    fun validate(expected: ObserverIpcExpectedV1, reply: ObserverIpcReplyV1, callerUid: Int,
                 callerPackages: Set<String>, nowElapsedMs: Long, maxAgeMs: Long = 2500): AuthenticatedObserverEvidenceV1 {
        require(callerUid > 0 && callerPackages == setOf("org.tessera.observer")) { "observer-ipc-identity-mismatch" }
        require(expected.requestId.matches(uuid) && expected.executionId.matches(uuid)) { "observer-ipc-request-invalid" }
        require(expected.challenge.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "observer-ipc-challenge-invalid" }
        require(expected.bootTimebaseId.matches(bootId) && reply.bootTimebaseId == expected.bootTimebaseId) { "observer-ipc-timebase-mismatch" }
        require(reply.protocol == "tessera.observer-ipc/1" && reply.outcome == "CANDIDATE_EVIDENCE") { "observer-ipc-refused" }
        require(reply.requestId == expected.requestId && reply.executionId == expected.executionId && reply.challenge == expected.challenge) { "observer-ipc-correlation-mismatch" }
        require(reply.packageName == expected.packageName && reply.windowId == expected.windowId) { "observer-ipc-window-mismatch" }
        require(expected.dispatchElapsedMs >= 0 && reply.capturedElapsedMs > expected.dispatchElapsedMs &&
            nowElapsedMs >= reply.capturedElapsedMs && nowElapsedMs - reply.capturedElapsedMs <= maxAgeMs) { "observer-ipc-stale-or-pre-dispatch" }
        require(reply.complete) { "observer-ipc-incomplete" }
        require(reply.digest.matches(digest)) { "observer-ipc-digest-invalid" }
        require(reply.observerPackage == "org.tessera.observer") { "observer-ipc-package-mismatch" }
        require(reply.artifactIdentity.startsWith("org.tessera.observer/")) { "observer-ipc-artifact-invalid" }
        return AuthenticatedObserverEvidenceV1(expected.requestId, expected.executionId, expected.packageName,
            expected.windowId, reply.capturedElapsedMs, reply.digest, expected.bootTimebaseId, reply.artifactIdentity, callerUid)
    }
}
