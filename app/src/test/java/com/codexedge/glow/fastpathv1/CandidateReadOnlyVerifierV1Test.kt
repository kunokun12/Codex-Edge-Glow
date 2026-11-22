package com.codexedge.glow.fastpathv1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CandidateReadOnlyVerifierV1Test {
    private val digest = "a".repeat(64)

    private fun root(pkgWindow: Int = 7) = CandidateUiSnapshotFingerprintV2.Node(
        0, -1, 0, pkgWindow, null, null, "com.example.reader:id/root", "android.view.ViewGroup",
        true, true, false, false, false, false, false)

    private fun marker(index: Int = 1, window: Int = 7, description: String? = "Continue") =
        CandidateUiSnapshotFingerprintV2.Node(index, 0, 1, window, null, description,
            "com.example.reader:id/continue", "android.widget.Button", true, true, true, true,
            false, false, false)

    private fun snapshot(
        nodes: List<CandidateUiSnapshotFingerprintV2.Node> = listOf(root(), marker()),
        packageName: String = "com.example.reader",
        window: Int = 7,
        before: Int = window,
        after: Int = window,
        captured: Long = 101,
        complete: Boolean = true
    ) = CandidateUiSnapshotFingerprintV2.Snapshot(packageName, window, before, after, captured,
        complete, !complete, complete, false, null, null, nodes)

    private fun expectation(s: CandidateUiSnapshotFingerprintV2.Snapshot,
                            fingerprint: String? = null,
                            pkg: String = s.packageName,
                            window: Int = s.windowId,
                            markers: List<CandidateRequiredMarkerV1> = listOf(
                                CandidateRequiredMarkerV1(CandidateMarkerFieldV1.DESCRIPTION, "Continue")),
                            dispatch: Long = 100) = CandidateDestinationExpectationV1(
        pkg, window, fingerprint ?: CandidateUiSnapshotFingerprintV2.fingerprint(s, pkg, window, 102), markers, dispatch)

    private fun evidence(s: CandidateUiSnapshotFingerprintV2.Snapshot = snapshot(),
                         build: String = digest,
                         independence: String = "separate-observer-process") = CandidateObservedEvidenceV1(
        s, CandidateObserverProvenanceV1(independence, build, "candidate-observer-process"))

    private fun evaluate(s: CandidateUiSnapshotFingerprintV2.Snapshot = snapshot(),
                         expected: CandidateDestinationExpectationV1 = expectation(s),
                         now: Long = 102,
                         observerBuild: String? = digest,
                         claimedBuild: String = digest,
                         independence: String = "separate-observer-process") =
        CandidateReadOnlyVerifierV1.verify(CandidateReadOnlyEvidenceReaderV1 { evidence(s, claimedBuild, independence) },
            expected, now, observerBuild)

    @Test fun candidateDestinationNeedsFreshIndependentReadOnlyEvidence() {
        assertEquals(CandidateVerificationOutcomeV1.CANDIDATE_MATCH, evaluate())
    }

    @Test fun destinationFingerprintMismatchFails() {
        val s = snapshot()
        val wrong = expectation(s, fingerprint = "0".repeat(64))
        assertEquals(CandidateVerificationOutcomeV1.DESTINATION_MISMATCH, evaluate(s, wrong))
    }

    @Test fun packageAndWindowMismatchFail() {
        val s = snapshot()
        assertEquals(CandidateVerificationOutcomeV1.PACKAGE_WINDOW_MISMATCH,
            evaluate(s, expectation(s, fingerprint = "0".repeat(64), pkg = "com.example.other")))
        val otherWindow = snapshot(window = 8, before = 8, after = 8, nodes = listOf(root(8), marker(window = 8)))
        assertEquals(CandidateVerificationOutcomeV1.PACKAGE_WINDOW_MISMATCH,
            evaluate(otherWindow, expectation(otherWindow, fingerprint = "0".repeat(64), window = 9)))
    }

    @Test fun staleAndPreDispatchEvidenceFail() {
        val stale = snapshot(captured = 100)
        assertEquals(CandidateVerificationOutcomeV1.STALE_EVIDENCE,
            evaluate(stale, expectation(stale, dispatch = 0), now = 2601))
        val before = snapshot(captured = 100)
        assertEquals(CandidateVerificationOutcomeV1.PRE_DISPATCH_EVIDENCE,
            evaluate(before, expectation(before, dispatch = 100)))
    }

    @Test fun incompleteEvidenceAndMissingOrAmbiguousMarkersFail() {
        val incomplete = snapshot(complete = false)
        assertEquals(CandidateVerificationOutcomeV1.INCOMPLETE_EVIDENCE,
            evaluate(incomplete, expectation(snapshot(), fingerprint = CandidateUiSnapshotFingerprintV2.fingerprint(
                snapshot(), "com.example.reader", 7, 102))))
        val missing = snapshot(nodes = listOf(root(), marker(description = "Continue loading")))
        assertEquals(CandidateVerificationOutcomeV1.MARKER_MISSING, evaluate(missing, expectation(missing)))
        val ambiguous = snapshot(nodes = listOf(root(), marker(), marker(index = 2)))
        assertEquals(CandidateVerificationOutcomeV1.AMBIGUOUS_MARKER, evaluate(ambiguous, expectation(ambiguous)))
    }

    @Test fun absentCrashedOrUnapprovedObserverFailsClosed() {
        val s = snapshot()
        val expected = expectation(s)
        assertEquals(CandidateVerificationOutcomeV1.OBSERVER_UNAVAILABLE,
            CandidateReadOnlyVerifierV1.verify(CandidateReadOnlyEvidenceReaderV1 { null }, expected, 102, digest))
        assertEquals(CandidateVerificationOutcomeV1.OBSERVER_UNAVAILABLE,
            CandidateReadOnlyVerifierV1.verify(CandidateReadOnlyEvidenceReaderV1 { error("observer-crash") }, expected, 102, digest))
        assertEquals(CandidateVerificationOutcomeV1.OBSERVER_PROVENANCE_MISMATCH,
            evaluate(s, expected, observerBuild = null))
        assertEquals(CandidateVerificationOutcomeV1.OBSERVER_PROVENANCE_MISMATCH,
            evaluate(s, expected, claimedBuild = "b".repeat(64)))
        assertEquals(CandidateVerificationOutcomeV1.OBSERVER_PROVENANCE_MISMATCH,
            evaluate(s, expected, independence = "same-process"))
    }

    @Test fun evidenceReaderIsCalledOnceAndExecutorSuccessIsNotAnInput() {
        val s = snapshot()
        val expected = expectation(s)
        var acquisitions = 0
        val reader = CandidateReadOnlyEvidenceReaderV1 { acquisitions++; evidence(s) }
        assertEquals(CandidateVerificationOutcomeV1.CANDIDATE_MATCH,
            CandidateReadOnlyVerifierV1.verify(reader, expected, 102, digest))
        assertEquals(1, acquisitions)
    }

    @Test fun claimedMatchingProvenanceStillCannotProduceM1VerifiedOutcome() {
        val s = snapshot()
        val forgedClaim = evidence(s, build = digest)
        val reader = CandidateReadOnlyEvidenceReaderV1 { forgedClaim }
        val result = CandidateReadOnlyVerifierV1.verify(reader, expectation(s), 102, digest)
        assertEquals(CandidateVerificationOutcomeV1.CANDIDATE_MATCH, result)
        assertFalse(result.name == "VERIFIED")
    }
}
