package com.codexedge.glow.fastpathv1

import java.security.MessageDigest

/**
 * Experimental M7.1 candidate only. This algorithm is not part of the M1 wire
 * contract and is deliberately not connected to the registry, receiver, or
 * production execution engine.
 *
 * Input must be a complete, single-window Accessibility snapshot. The caller
 * supplies a monotonic `nowElapsedRealtimeMs`; wall-clock time is never used for
 * freshness. Bounds and transient Android window IDs are validated but omitted
 * from the digest. Array order and explicit parent/depth relationships are
 * preserved.
 */
internal object CandidateUiSnapshotFingerprintV2 {
    const val ALGORITHM = "tessera.ui-state-candidate/2"
    const val MAX_NODES = 4096
    const val MAX_DEPTH = 64
    const val MAX_CANONICAL_BYTES = 1_048_576
    const val MAX_AGE_MS = 2500L
    private const val MAX_PROCESSING_NANOS = 250_000_000L
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    data class Node(
        val index: Int,
        val parentIndex: Int,
        val depth: Int,
        val windowId: Int,
        val text: String?,
        val description: String?,
        val resourceId: String?,
        val className: String?,
        val visible: Boolean,
        val enabled: Boolean,
        val clickable: Boolean,
        val focusable: Boolean,
        val selected: Boolean,
        val checked: Boolean,
        val password: Boolean
    )

    data class Snapshot(
        val packageName: String,
        val windowId: Int,
        val windowIdBefore: Int,
        val windowIdAfter: Int,
        val capturedAtElapsedRealtimeMs: Long,
        val treeComplete: Boolean,
        val truncated: Boolean,
        val collectionSuccess: Boolean,
        val timeBudgetExceeded: Boolean,
        val truncationReason: String?,
        val traversalError: String?,
        val nodes: List<Node>
    )

    /**
     * Validates the evidence and returns a lowercase SHA-256 hex digest. A
     * candidate digest is useful for vector comparison only; it cannot
     * authorize route execution until M1 approval and production provenance.
     */
    fun fingerprint(
        snapshot: Snapshot,
        expectedPackage: String,
        expectedWindowId: Int,
        nowElapsedRealtimeMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
        processingClockNanos: () -> Long = System::nanoTime
    ): String {
        val started = processingClockNanos()
        require(packagePattern.matches(snapshot.packageName)) { "package-invalid" }
        require(snapshot.packageName == expectedPackage) { "package-mismatch" }
        require(snapshot.windowId >= 0 && snapshot.windowId == expectedWindowId) { "window-mismatch" }
        require(snapshot.windowIdBefore == snapshot.windowId && snapshot.windowIdAfter == snapshot.windowId) {
            "window-unstable"
        }
        require(snapshot.capturedAtElapsedRealtimeMs >= 0L && nowElapsedRealtimeMs >= snapshot.capturedAtElapsedRealtimeMs) {
            "capture-time-invalid"
        }
        require(maxAgeMs >= 1L && maxAgeMs <= MAX_AGE_MS &&
            nowElapsedRealtimeMs - snapshot.capturedAtElapsedRealtimeMs <= maxAgeMs) {
            "snapshot-stale"
        }
        require(snapshot.treeComplete && !snapshot.truncated && snapshot.collectionSuccess && !snapshot.timeBudgetExceeded) {
            "snapshot-incomplete"
        }
        require(snapshot.truncationReason == null && snapshot.traversalError == null) { "snapshot-has-traversal-error" }
        require(snapshot.nodes.isNotEmpty() && snapshot.nodes.size <= MAX_NODES) { "node-count-invalid" }

        var rootCount = 0
        var previousDepth = 0
        var previousParent = -1
        snapshot.nodes.forEachIndexed { expectedIndex, node ->
            require(processingClockNanos() - started <= MAX_PROCESSING_NANOS) { "fingerprint-time-limit" }
            require(node.index == expectedIndex) { "node-index-invalid" }
            require(node.windowId == snapshot.windowId) { "node-window-mismatch" }
            require(node.depth in 0..MAX_DEPTH) { "node-depth-invalid" }
            require(expectedIndex == 0 || node.depth >= previousDepth) { "breadth-first-order-invalid" }
            if (node.parentIndex == -1) {
                rootCount++
                require(expectedIndex == 0 && node.depth == 0) { "root-order-invalid" }
            } else {
                require(node.parentIndex in 0 until expectedIndex) { "parent-index-invalid" }
                require(snapshot.nodes[node.parentIndex].depth + 1 == node.depth) { "parent-depth-invalid" }
                if (expectedIndex > 0 && node.depth == previousDepth)
                    require(node.parentIndex >= previousParent) { "breadth-first-sibling-order-invalid" }
            }
            require(node.text == null || node.text.length <= 1000) { "text-length-invalid" }
            require(node.description == null || node.description.length <= 1000) { "description-length-invalid" }
            require(node.resourceId == null || node.resourceId.length <= 1000) { "resource-id-length-invalid" }
            require(node.className == null || node.className.length <= 200) { "class-name-length-invalid" }
            previousDepth = node.depth
            previousParent = node.parentIndex
        }
        require(rootCount == 1) { "root-count-invalid" }

        val digestInput = mapOf<String, Any?>(
            "fingerprintAlgorithm" to ALGORITHM,
            "packageName" to snapshot.packageName,
            "nodes" to snapshot.nodes.map { node ->
                mapOf<String, Any?>(
                    "parentIndex" to node.parentIndex,
                    "depth" to node.depth,
                    "text" to if (node.password) null else node.text,
                    "description" to if (node.password) null else node.description,
                    "resourceId" to node.resourceId,
                    "className" to node.className,
                    "visible" to node.visible,
                    "enabled" to node.enabled,
                    "clickable" to node.clickable,
                    "focusable" to node.focusable,
                    "selected" to node.selected,
                    "checked" to node.checked,
                    "password" to node.password
                )
            }
        )
        val canonical = CanonicalRouteV1.encode(digestInput)
        val bytes = canonical.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CANONICAL_BYTES) { "canonical-size-limit" }
        require(processingClockNanos() - started <= MAX_PROCESSING_NANOS) { "fingerprint-time-limit" }
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
