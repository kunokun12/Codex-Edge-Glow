package com.codexedge.glow.fastpathv1

import java.security.MessageDigest
import java.text.Normalizer

/** Experimental M9 privacy-first projection. Never authorizes route execution. */
internal object UiStateFingerprintV1Experimental {
    const val ALGORITHM = "tessera.ui-state/1"
    const val APPROVAL_STATE = "EXPERIMENTAL_NOT_APPROVED"
    const val MAX_NODES = 4096
    const val MAX_DEPTH = 64
    const val MAX_CANONICAL_BYTES = 1_048_576
    const val MAX_AGE_MS = 2500L
    private const val MAX_NANOS = 250_000_000L
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    // Compiled policy, not supplied by a route or request.
    private val markers = mapOf(
        "screen.home" to setOf("Home"),
        "screen.done" to setOf("Done"),
        "navigation.settings" to setOf("Settings")
    )

    data class Digests(val uiStateFingerprint: String, val evidenceSnapshotDigest: String, val markerIds: Set<String>)

    fun validateMarkerIds(ids: List<String>) {
        require(ids.size == ids.toSet().size && ids.all { markers.containsKey(it) }) { "marker-policy-refused" }
    }

    fun fingerprint(
        snapshot: CandidateUiSnapshotFingerprintV2.Snapshot,
        expectedPackage: String,
        expectedWindowId: Int,
        nowElapsedRealtimeMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
        processingClockNanos: () -> Long = System::nanoTime
    ): Digests {
        val started = processingClockNanos()
        require(packagePattern.matches(snapshot.packageName) && snapshot.packageName == expectedPackage) { "package-mismatch" }
        require(snapshot.windowId >= 0 && snapshot.windowId == expectedWindowId &&
            snapshot.windowIdBefore == snapshot.windowId && snapshot.windowIdAfter == snapshot.windowId) { "window-mismatch" }
        require(snapshot.capturedAtElapsedRealtimeMs >= 0 && nowElapsedRealtimeMs >= snapshot.capturedAtElapsedRealtimeMs) { "capture-time-invalid" }
        require(maxAgeMs in 1..MAX_AGE_MS && nowElapsedRealtimeMs - snapshot.capturedAtElapsedRealtimeMs <= maxAgeMs) { "snapshot-stale" }
        require(snapshot.treeComplete && !snapshot.truncated && snapshot.collectionSuccess && !snapshot.timeBudgetExceeded &&
            snapshot.truncationReason == null && snapshot.traversalError == null) { "snapshot-incomplete" }
        require(snapshot.nodes.isNotEmpty() && snapshot.nodes.size <= MAX_NODES) { "node-count-invalid" }

        var roots = 0
        var previousDepth = 0
        var previousParent = -1
        val counts = markers.keys.associateWith { 0 }.toMutableMap()
        val nodes = snapshot.nodes.mapIndexed { expectedIndex, node ->
            require(processingClockNanos() - started <= MAX_NANOS) { "fingerprint-time-limit" }
            require(node.index == expectedIndex && node.windowId == snapshot.windowId) { "node-identity-invalid" }
            require(node.depth in 0..MAX_DEPTH) { "node-depth-invalid" }
            require(expectedIndex == 0 || node.depth >= previousDepth) { "breadth-first-order-invalid" }
            if (node.parentIndex == -1) {
                roots++
                require(expectedIndex == 0 && node.depth == 0) { "root-order-invalid" }
            } else {
                require(node.parentIndex in 0 until expectedIndex &&
                    snapshot.nodes[node.parentIndex].depth + 1 == node.depth) { "parent-hierarchy-invalid" }
            }
            if (expectedIndex > 0 && node.depth == previousDepth && node.parentIndex != -1)
                require(node.parentIndex >= previousParent) { "breadth-first-sibling-order-invalid" }
            require(node.text == null || node.text.length <= 1000) { "node-text-invalid" }
            require(node.description == null || node.description.length <= 1000) { "node-description-invalid" }
            require(node.resourceId == null || node.resourceId.length <= 1000) { "node-resource-id-invalid" }
            require(node.className == null || node.className.length <= 200) { "node-class-invalid" }
            var marker: String? = null
            if (!node.password && node.text != null) {
                val normalized = Normalizer.normalize(node.text, Normalizer.Form.NFC)
                markers.forEach { (id, values) -> if (normalized in values) { marker = id; counts[id] = counts.getValue(id) + 1 } }
            }
            previousDepth = node.depth
            previousParent = node.parentIndex
            mapOf<String, Any?>(
                "parentIndex" to node.parentIndex, "depth" to node.depth, "marker" to marker,
                "resourceId" to node.resourceId, "className" to node.className,
                "visible" to node.visible, "enabled" to node.enabled, "clickable" to node.clickable,
                "focusable" to node.focusable, "selected" to node.selected, "checked" to node.checked,
                "password" to node.password
            )
        }
        require(roots == 1) { "root-count-invalid" }
        require(counts.values.all { it <= 1 }) { "ambiguous-marker" }
        val state = mapOf<String, Any?>("fingerprintAlgorithm" to ALGORITHM,
            "packageName" to snapshot.packageName, "windowId" to snapshot.windowId, "nodes" to nodes)
        val evidence = mapOf<String, Any?>("evidenceSchema" to "tessera.evidence-snapshot/1",
            "packageName" to snapshot.packageName, "windowId" to snapshot.windowId,
            "windowIdBefore" to snapshot.windowIdBefore, "windowIdAfter" to snapshot.windowIdAfter,
            "capturedAtElapsedRealtimeMs" to snapshot.capturedAtElapsedRealtimeMs,
            "treeComplete" to snapshot.treeComplete, "nodes" to nodes)
        val stateCanonical = CanonicalRouteV1.encode(state)
        val evidenceCanonical = CanonicalRouteV1.encode(evidence)
        require(stateCanonical.toByteArray(Charsets.UTF_8).size <= MAX_CANONICAL_BYTES &&
            evidenceCanonical.toByteArray(Charsets.UTF_8).size <= MAX_CANONICAL_BYTES) { "canonical-size-limit" }
        require(processingClockNanos() - started <= MAX_NANOS) { "fingerprint-time-limit" }
        return Digests(sha256(stateCanonical), sha256(evidenceCanonical), counts.filterValues { it == 1 }.keys)
    }

    private fun sha256(canonical: String): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
