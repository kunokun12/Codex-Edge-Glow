package com.codexedge.glow.fastpathv1

import java.text.Normalizer

/** Candidate-only result labels. None of these values are M1 wire responses. */
internal enum class CandidateVerificationOutcomeV1 {
    CANDIDATE_MATCH,
    DESTINATION_MISMATCH,
    STALE_EVIDENCE,
    PRE_DISPATCH_EVIDENCE,
    PACKAGE_WINDOW_MISMATCH,
    INCOMPLETE_EVIDENCE,
    MARKER_MISSING,
    AMBIGUOUS_MARKER,
    OBSERVER_UNAVAILABLE,
    OBSERVER_PROVENANCE_MISMATCH,
    UNKNOWN
}

internal enum class CandidateMarkerFieldV1 { TEXT, DESCRIPTION, RESOURCE_ID, CLASS_NAME }

internal data class CandidateRequiredMarkerV1(val field: CandidateMarkerFieldV1, val value: String)

internal data class CandidateObserverProvenanceV1(
    val independenceClass: String,
    val observerBuildSha256: String,
    val componentId: String
)

internal data class CandidateObservedEvidenceV1(
    val snapshot: CandidateUiSnapshotFingerprintV2.Snapshot,
    val provenance: CandidateObserverProvenanceV1
)

/**
 * Read-only acquisition boundary. It intentionally has no Accessibility
 * dispatch, navigation, or mutation method. A production implementation and
 * artifact attestation are not present in this candidate.
 */
internal fun interface CandidateReadOnlyEvidenceReaderV1 {
    fun capture(): CandidateObservedEvidenceV1?
}

internal data class CandidateDestinationExpectationV1(
    val packageName: String,
    val windowId: Int,
    val destinationFingerprint: String,
    val requiredMarkers: List<CandidateRequiredMarkerV1>,
    val dispatchPossibleElapsedRealtimeMs: Long,
    val maxAgeMs: Long = CandidateUiSnapshotFingerprintV2.MAX_AGE_MS
)

/**
 * Pure candidate policy evaluator. It does not inspect executor success flags,
 * and its output is intentionally not serializable as an M1 `route.verify`
 * response. The expected build digest must come from an independently approved
 * artifact registry; a matching string alone is not cryptographic attestation.
 */
internal object CandidateReadOnlyVerifierV1 {
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun verify(
        reader: CandidateReadOnlyEvidenceReaderV1,
        expected: CandidateDestinationExpectationV1,
        nowElapsedRealtimeMs: Long,
        approvedObserverBuildSha256: String?
    ): CandidateVerificationOutcomeV1 {
        if (!digestPattern.matches(expected.destinationFingerprint) ||
            expected.dispatchPossibleElapsedRealtimeMs < 0 || expected.maxAgeMs < 1L ||
            expected.maxAgeMs > CandidateUiSnapshotFingerprintV2.MAX_AGE_MS ||
            expected.requiredMarkers.any { it.value.isEmpty() || it.value.length > 1000 }) {
            return CandidateVerificationOutcomeV1.UNKNOWN
        }
        val evidence = try { reader.capture() } catch (_: Exception) { null }
            ?: return CandidateVerificationOutcomeV1.OBSERVER_UNAVAILABLE
        val provenance = evidence.provenance
        if (provenance.independenceClass != "separate-observer-process" ||
            !digestPattern.matches(provenance.observerBuildSha256) ||
            provenance.componentId.isBlank() ||
            approvedObserverBuildSha256 == null ||
            provenance.observerBuildSha256 != approvedObserverBuildSha256) {
            return CandidateVerificationOutcomeV1.OBSERVER_PROVENANCE_MISMATCH
        }
        val snapshot = evidence.snapshot
        if (snapshot.packageName != expected.packageName || snapshot.windowId != expected.windowId ||
            snapshot.windowIdBefore != expected.windowId || snapshot.windowIdAfter != expected.windowId) {
            return CandidateVerificationOutcomeV1.PACKAGE_WINDOW_MISMATCH
        }
        if (snapshot.capturedAtElapsedRealtimeMs <= expected.dispatchPossibleElapsedRealtimeMs) {
            return CandidateVerificationOutcomeV1.PRE_DISPATCH_EVIDENCE
        }
        if (nowElapsedRealtimeMs < snapshot.capturedAtElapsedRealtimeMs ||
            nowElapsedRealtimeMs - snapshot.capturedAtElapsedRealtimeMs > expected.maxAgeMs) {
            return CandidateVerificationOutcomeV1.STALE_EVIDENCE
        }
        val fingerprint = try {
            CandidateUiSnapshotFingerprintV2.fingerprint(snapshot, expected.packageName,
                expected.windowId, nowElapsedRealtimeMs, expected.maxAgeMs)
        } catch (_: IllegalArgumentException) {
            return when {
                snapshot.packageName != expected.packageName || snapshot.windowId != expected.windowId ->
                    CandidateVerificationOutcomeV1.PACKAGE_WINDOW_MISMATCH
                !snapshot.treeComplete || snapshot.truncated || !snapshot.collectionSuccess ||
                    snapshot.timeBudgetExceeded || snapshot.truncationReason != null || snapshot.traversalError != null ->
                    CandidateVerificationOutcomeV1.INCOMPLETE_EVIDENCE
                nowElapsedRealtimeMs < snapshot.capturedAtElapsedRealtimeMs ||
                    nowElapsedRealtimeMs - snapshot.capturedAtElapsedRealtimeMs > expected.maxAgeMs ->
                    CandidateVerificationOutcomeV1.STALE_EVIDENCE
                else -> CandidateVerificationOutcomeV1.UNKNOWN
            }
        } catch (_: Exception) {
            return CandidateVerificationOutcomeV1.UNKNOWN
        }
        if (fingerprint != expected.destinationFingerprint) return CandidateVerificationOutcomeV1.DESTINATION_MISMATCH

        for (marker in expected.requiredMarkers) {
            val expectedValue = Normalizer.normalize(marker.value, Normalizer.Form.NFC)
            val matches = snapshot.nodes.filter { node ->
                if (!node.visible || node.password) return@filter false
                val actual = when (marker.field) {
                    CandidateMarkerFieldV1.TEXT -> node.text
                    CandidateMarkerFieldV1.DESCRIPTION -> node.description
                    CandidateMarkerFieldV1.RESOURCE_ID -> node.resourceId
                    CandidateMarkerFieldV1.CLASS_NAME -> node.className
                }
                actual != null && Normalizer.normalize(actual, Normalizer.Form.NFC) == expectedValue
            }
            if (matches.isEmpty()) return CandidateVerificationOutcomeV1.MARKER_MISSING
            if (matches.size != 1) return CandidateVerificationOutcomeV1.AMBIGUOUS_MARKER
        }
        return CandidateVerificationOutcomeV1.CANDIDATE_MATCH
    }
}
