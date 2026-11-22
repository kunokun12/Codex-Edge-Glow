# Candidate independent observer architecture and provenance

**Status: candidate only; not approved for production verification.** This document describes the M7 read-only observer candidate and its limits. No `route.verify` M1 response is produced by it.

## Candidate Android boundary

- `CandidateObserverAccessibilityServiceV1` is declared as an Accessibility service in Android process `com.codexedge.glow:fastpath-observer`, separate from the receiver/executor process.
- Its service configuration requests window-content retrieval and window/content events. It does not request gesture capability. Source review confirms the service contains no `performAction`, click, gesture, text-entry, or navigation call.
- The service captures one active window on service connection and relevant Accessibility events. It traverses a single root breadth-first, preserving child order and parent/depth indices. It bounds nodes to 4096, depth to 64, traversal to 250 ms, snapshot bytes to 4 MiB, and total candidate processing to 500 ms. Incomplete evidence is discarded.
- It publishes one fresh snapshot in process memory. `CandidateObserverSnapshotProviderV1` runs in the same observer process, is `exported=false`, rejects non-same-UID callers and mutation methods, checks snapshot freshness, recomputes the candidate snapshot digest, and consumes the snapshot once. Raw UI evidence is not written to a journal or disk.
- The observer is not enabled by the app. Adding a manifest declaration does not enable an Accessibility service or change Android settings. No device settings were changed during implementation.

The process boundary is real at Android process configuration but is not an isolated UID, separate package, separate signing identity, or independently built APK. A compromised app UID or modified app can forge local data. This candidate therefore cannot claim production observer independence or provenance merely because the component is in another process.

## Evidence and provenance

The snapshot binds package, window before/after, wall observation time, monotonic capture time, tree completeness, node order, and traversal metadata. The candidate provider serves evidence only when the tree is complete and no older than 2500 ms. A verifier must additionally compare monotonic capture time strictly greater than the durable `DISPATCH_POSSIBLE` timestamp.

The candidate verifier is a pure read-only policy evaluator. It takes a reader with a `capture()` method and has no action port. It never accepts an executor success flag. It checks package/window, exact destination digest, required exact markers, freshness, post-dispatch capture, a pinned observer digest string, and the declared `separate-observer-process` class. Its result is named `CANDIDATE_MATCH`, not M1 `VERIFIED`, and cannot serialize to a `route.verify.result` payload.

The digest comparison is not cryptographic attestation. There is no authenticated process-to-process provenance protocol, no signed observer statement, and no independently approved observer build identity. The source tree cannot know its final APK SHA-256 while building itself; the artifact digest is recorded externally from the exact CI APK. Producer JSON or a matching digest string must not be treated as authority.

## Candidate artifact identity

When CI succeeds, record the SHA-256 of the exact APK that contains this candidate and link it to the source commit, workflow run, APK signer certificate SHA-256, test report, package/version, and build variant. This is a reproducible candidate artifact digest only. It is not an owner approval or an independent observer signature. Do not fill M1 `observerBuildSha256` in a production response until the observer artifact and provenance method are approved.

## Tests and untested claims

Automated tests cover deterministic host/Android fingerprint vector agreement, wrong destination/package/window, stale and pre-dispatch evidence, missing/ambiguous markers, unavailable/crashed reader, provenance mismatch, one acquisition, and the declared separate process/internal provider configuration. These are JVM/Robolectric and host tests. They do not enable the service, observe a live screen, prove cross-process operation on Android, prove crash recovery of the observer process, prove OS-level authenticity, or establish API 26 runtime behavior.

## Approval and next steps

An owner must approve the exact observer source, binary digest, signing identity, independence class, provenance verification, privacy behavior, and Android runtime test plan. The service must remain disabled until that review and any separate authorization to change Accessibility settings. Until then, `route.verify` remains unavailable and execution stays disabled.
