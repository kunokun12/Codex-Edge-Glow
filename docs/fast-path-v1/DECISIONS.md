# Tessera Fast Path v1 — M1 Architecture Decision Records

Status: M1 design decisions and executable host contract harness. The wire identifier remains **`tessera.fast-path/1`**. No deployed v1 implementation exists. M1 completes the pre-implementation contract by adding lifecycle/control messages and typed results; this is not an installed-client compatibility break. No Companion source or APK was changed.

## 1. Route custody, promotion, revocation, and rollback

**Decision.** Companion's app-private versioned registry is authoritative. Lifecycle is `DISCOVERED -> REVIEWED -> PROMOTED -> REVOKED`; only `PROMOTED` is executable. The host may discover/review and propose; a separate offline route authority signs the exact canonical route record. Companion verifies a pinned authority certificate/signature before persisting promotion. Execution always compares route ID/version/fingerprint and full definition with the local promoted entry. A matching hash or caller-supplied lifecycle claim is insufficient.

Promotion/revocation records carry an authority key ID, monotonically increasing registry epoch, issue time, route reference, and signature. Promotion expires after 24 hours unless renewed. A signed revocation tombstone is durable and wins over any earlier promotion. Companion rejects epoch regression and refuses the affected route when registry integrity/clock state is uncertain. Host `skills.sqlite` is a read-only source of candidates; it is never authority. Offline execution works from an unexpired local promoted record; revocation cannot reach an offline device until synchronization, so the 24-hour promotion expiry bounds (but does not eliminate) that exposure.

**Alternatives.** Trust host route hashes; keep authority only in host SQLite; ship unsigned route definitions; require online lookup on every run. Rejected because none establishes local approval during offline execution or controls replay/revocation.

**Reasoning/security.** Signed authority proves the promotion record came from a key trusted by the installed Companion, not that route review was correct or that the device clock is honest. Root public keys ship in the signed APK. Promotion private keys remain offline and are not included in this repository/archive. The keyset is versioned; rotation is a root-signed keyset update in a higher-version APK, with overlap and explicit retirement. App data can be restored/rolled back; Android app-private storage alone is not a hardware monotonic counter. A registry rollback detector is required, but its security is not established here.

**Performance.** Signature verification occurs only on promote/revoke; execution uses local indexed lookup and hash comparison. No network call is needed per route run.

**Failure/recovery.** Invalid signature, expired record, stale epoch, revoked fingerprint, rollback suspicion, or missing registry entry refuses before action. Never resurrect a revoked fingerprint; a corrected route receives a new version/fingerprint and new promotion.

**Required tests.** Valid/forged/wrong-authority signatures, expiry, revoke-before-execute, stale epoch, concurrent promotion, APK key rotation, restore/rollback, offline valid promotion and expiry.

**Evidence blocker.** Yes: trusted-time and anti-rollback behavior across app-data restore must be demonstrated before Companion implementation can claim durable revocation safety or before APK/live release.

## 2. IPC transport and caller identity

**Decision.** Proposed transport is one explicit, pinned-device ADB command to an exported `FastPathV1Receiver` guarded by `android.permission.DUMP`; request JSON is canonical UTF-8, base64url encoded in one `envelope_b64` extra, bounded to 64 KiB. Host uses `adb -s <pinned-serial> shell am broadcast --user current --receiver-foreground -n com.codexedge.glow/.FastPathV1Receiver -a com.codexedge.glow.FAST_PATH_V1 --es envelope_b64 <base64url>`. The receiver accepts only v1 and enqueues local work. A DUMP-protected read-only ContentProvider at `content://com.codexedge.glow.fastpath/v1` returns request-correlated durable results by request ID; the read call also supplies task/session/owner IDs. No shared singleton result file. Device-facing commands never auto-retry.

**Alternatives.** Existing v2.5 action receiver; implicit broadcasts; shell screenshots/coordinate automation; Binder service requiring a same-signature client; host polling per route step. Rejected for v1 because they either mix legacy behavior, broaden exposure, lack practical caller access from PRoot, or add per-step round trips.

**Reasoning/security.** `DUMP` authorizes privileged Android shell callers. Host ADB checks the pinned serial; Companion cannot independently verify that serial. It does **not** authenticate the Tessera host process, user intent, or a unique owner. UUID correlation is not authentication. Explicit component/action, exact v1 parser, pinned ADB serial, bounded request and read-only result provider reduce accidental routing but do not solve a malicious shell caller. Promotion signatures separately prevent unsigned route promotion. Do not claim cryptographic caller authentication.

**Performance.** One start command per route, local route steps, and result reads only at completion/status boundaries. Status calls are separate explicit read-only commands and are not retries. Target measurements are in `TEST-PLAN.md`.

**Failure/recovery.** ADB timeout after invocation is ambiguous: do not resend `route.execute`; query status/result using the same correlation tuple. Provider absence or stale response yields `RECOVERY_REQUIRED`/`UNKNOWN`, not success. Reject URI/query identity mismatches.

**Required tests.** Explicit component/permission manifest inspection; command envelope round trip; 64 KiB bound; wrong device/correlation; provider ACL; duplicate delivery; timeout/no-retry; unauthorized ordinary app denial; unknown/missing version rejection before legacy dispatch.

**Evidence blocker.** Yes: actual DUMP permission behavior, `am` argument limits, provider response behavior on supported Android API 26+, and threat model for shell callers require implementation/emulator evidence. No IPC is implemented by M1.

## 3. Authoritative execution lease

**Decision.** Companion is the sole authority for the active task lease. `route.execute` acquires it atomically in Companion storage using `(deviceSerial, taskId, requestId, sessionId, ownerId)`. It is based on `SystemClock.elapsedRealtime()`; initial idle TTL is 30 s, renewed internally by the live executor at <=5 s intervals, with a fixed maximum execution window of 120 s. Status, cancel, evidence and verifier reads do not renew it. Host SQLite execution lease remains a local duplicate-work optimization only and cannot override Companion. IDs remain correlation, not authentication.

**Alternatives.** Existing three lease layers authoritative; host lease authority; lease renewal by host heartbeats; no lease. Rejected because of clock mismatch, ambiguity and route ownership races.

**Security/performance.** Companion transaction plus a process-local execution mutex prevents concurrent task execution. Lease does not establish caller identity. One local heartbeat avoids host round trips.

**Failure/recovery.** Expiry stops new steps. A task past any `DISPATCH_POSSIBLE` remains recovery-required; expiry never makes it retryable. Companion process death expires the lease and recovery reads journal state.

**Tests.** Concurrent start race, tuple mismatch, monotonic expiry/renewal, process death, host/Companion lease disagreement, no renewal from reads/cancel.

**Evidence blocker.** No for contract; yes before claiming persistence/monotonic clock and process scheduling semantics on Android.

## 4. Companion-owned per-step journal

**Decision.** Companion app-private SQLite is authoritative for execution steps and dispatch boundaries, with foreign keys, WAL, `synchronous=FULL`, transactional sequence allocation, and a hash-linked append-only event stream. Journal `DISPATCH_POSSIBLE` and commit before calling accessibility. Host `durable-task-journal.sqlite` remains authoritative for host request allocation and a mirror/audit of Companion sequence/digests; it is not allowed to declare a dispatch absent when Companion has one. Raw UI trees are not journaled.

**Alternatives.** Host-only journal, receiver SharedPreferences bit, dual commit across host/device. Rejected: host cannot atomically commit with Android action; preferences do not capture route-step recovery; cross-device dual commit cannot be atomic.

**Security/performance.** Private app storage limits casual access; it is not hardware anti-rollback. FULL commit is required before action and adds one durable transaction per step. Host mirror is append-only and reconciled by sequence/digest.

**Failure/recovery.** Journal commit failure means no action. After commit, missing action receipt/process death is uncertain. On host/Companion disagreement, Companion history governs dispatch possibility; return `RECOVERY_REQUIRED`, preserve both records and independently observe before any later user-directed action. Never replay a possibly dispatched step.

**Tests.** Storage-full/commit failure, crash at each state edge, reopen/recovery, sequence/hash-chain verification, duplicate task IDs, journal divergence, process concurrent writers.

**Evidence blocker.** Yes: Android SQLite durability, file-system sync, backup/restore and crash behavior must be tested on emulator before release claims.

## 5. Cancellation ordering and atomicity

**Decision.** A durable cancellation flag is checked within the same Companion DB transaction that checks lease/owner and appends `DISPATCH_POSSIBLE`. Before that transaction commits, cancellation wins and no action occurs (`CANCELLED`). After it commits, the current step is uncertain; accept cancellation as `stopAfterCurrentStep`, prevent later steps, and return `RECOVERY_REQUIRED`. Cancellation does not clear the boundary or claim the action was prevented.

**Alternatives.** Best-effort in-memory flag; allow current whole route to finish; roll back dispatch marker on cancellation. Rejected as racy or unsafe.

**Security/performance.** Every mutating cancel checks full correlation tuple. A small serialized executor/DB transaction establishes ordering without holding locks during accessibility observation.

**Failure/recovery.** If cancellation command times out, status query only; no resend of action. If cancel races after boundary, no following step even if action receipt is positive.

**Tests.** Deterministic barriers immediately before and after commit; concurrent cancel; cancellation during observation; cancel response loss; no subsequent dispatch.

**Evidence blocker.** No for state contract; yes to demonstrate Android BroadcastReceiver/service scheduling and atomic ordering.

## 6. Independent destination verification

**Decision.** Verification is a distinct read-only observer path with separate implementation/process and no access to the action receiver's result file as evidence. It must capture fresh package/window/UI evidence and independently compute the expected destination fingerprint. An action receipt is never verification. Proposed M1 IPC operation is `route.verify`, routed to a read-only verifier capability. It may return `VERIFIED` only with a named independence class/build digest and <=2500 ms evidence.

**Alternatives.** Trust `ACTION_CLICK` return; ask executor to self-report; reread same service with no separation. Rejected because they share action decision/failure modes.

**Security/performance.** Separate process/build reduces shared-code faults, not OS/framework/root compromise. Same app signer or same service is not automatically independent. Verification adds one destination observation after the route.

**Failure/recovery.** Missing, stale, wrong package/window, different destination, or unverifiable producer cannot yield success; return `RECOVERY_REQUIRED` or `VERIFICATION_FAILED`.

**Tests.** Inject false action receipt, stale/verifier replay, independent observer success/failure, wrong package/window, process/build provenance mismatch.

**Evidence blocker.** Yes: no currently demonstrated independent observer/build exists. This blocks APK release and live execution; contract-only Companion work may implement the interface but must not label it verified.

## 7. Snapshot freshness and clock boundaries

**Decision.** Companion uses monotonic elapsed time for in-process snapshot age, lease and deadlines. A request's `expiresAtEpochMs` is checked once at receipt and converted to a bounded monotonic deadline (maximum request TTL 5 minutes). Snapshot capture and step observations are aged using the same Companion monotonic clock; diagnostics include wall time. Host and device uptime are never compared. Host applies round-trip wall-clock freshness as a secondary bound only.

**Alternatives.** Compare host/device monotonic timestamps; trust wall clocks; freshness based only on `ageMs` supplied by producer. Rejected as incomparable/spoofable.

**Security/performance.** A same-process monotonic clock avoids wall-clock rollback during a task. Wall time can still be wrong across reboot/app-data restore; TTLs are bounded. Re-observe immediately before each step.

**Failure/recovery.** Clock rollback, reboot during task, future timestamp, incomplete traversal, stale age or unstable window refuses before dispatch; after dispatch boundary, recovery required.

**Tests.** Fake monotonic/wall clocks, rollback/future cases, reboot marker, slow traversal, age boundary and window race.

**Evidence blocker.** Yes: reboot and elapsedRealtime behavior and Android API instrumentation need emulator validation.

## 8. Canonical route fingerprinting

**Decision.** New v1 fingerprint is lowercase SHA-256 of canonical UTF-8 JSON over `{fingerprintVersion, routeId, routeVersion, routeDefinition}`, with `fingerprintVersion="tessera.route-canonical/1"`. Canonicalization: NFC strings, object keys sorted by Unicode scalar/code point, minimal JSON separators, non-ASCII emitted as UTF-8, standard JSON escaping for quotes/backslash/control characters, integers only (no floats/NaN), arrays preserve order, duplicate keys and unpaired surrogates rejected. Definition includes package, exact signer/version constraint, origin/destination predicates, ordered step selectors, fixed uniqueness policy, same-window policy and safety bounds. Execution/lifecycle/evidence timestamps are excluded.

**Alternatives.** Existing `JSON.stringify({id,package,steps})`; RFC 8785/JCS; sorted-key serialization without Unicode normalization. Legacy algorithm is preserved for old rows but not reused for v1. A named custom version is selected to make Kotlin/Node vectors exact.

**Security/performance.** Hash proves canonical content equality, not approval. Compute on promotion and verify at execute; route size <=64 KiB/32 steps.

**Failure/recovery.** Any mismatch is `REFUSED`; never rewrite fingerprint. New route means new version/promotion.

**Tests.** Shared ASCII/NFC/supplementary Unicode vectors, invalid float/duplicate/surrogate, key order invariance, step order sensitivity, changed policy invalidation.

**Evidence blocker.** No for contract; cross-language implementation must pass vectors before interoperability claims.

## 9. App updates and route invalidation

**Decision.** v1 routes pin package name, signing certificate SHA-256, version name and version code exactly by default. No wildcard range in v1. App update/signature drift, locale or accessibility semantics change, origin mismatch, selector ambiguity, expected post-state mismatch, protocol/build incompatibility or evidence revocation invalidates execution and moves route to review. Re-promotion creates a new route version/fingerprint; automatic re-learning does not authorize execution.

**Alternatives.** Package-only binding or version ranges. Rejected until evidence establishes safe compatibility.

**Security/performance.** Exact package/signer is checked before route start and at each observation; version metadata cached only within current task. This may invalidate routes frequently but fails closed.

**Failure/recovery.** Drift before first dispatch refuses. Drift after dispatch stops further steps and enters recovery.

**Tests.** Package clone, signer rotation, version changes, locale/resource-ID change, mid-route app/window switch.

**Evidence blocker.** No design blocker; app signing/version behavior needs actual test app/emulator evidence.

## 10. Privacy and evidence retention

**Decision.** Persist route predicates, canonical route, fingerprints, signed promotion/revocation records, per-step state IDs, bounded safe reason codes and evidence digests only. Do not persist raw accessibility node trees, text values, screenshots, bounds, coordinates, form content, or secrets in route registry/journal. Capture trees are memory-only, bounded to 4096 nodes/250 ms and discarded after step verification. Terminal task/journal metadata retention is 30 days after resolution; unresolved recovery records remain until explicit reconciliation, then 30 days. Promotion evidence references/digests remain while the route is active and 90 days after revocation.

**Alternatives.** Persist full snapshots for easier recovery; retain forever; persist only opaque status. Rejected due privacy exposure or insufficient audit/recovery context.

**Security/performance.** Data minimization reduces sensitive spill; hashes may still correlate observations and are access-controlled. Deletion is best-effort on flash and not a cryptographic erase guarantee.

**Failure/recovery.** If sufficient safe evidence cannot be retained to diagnose/reconcile, retain state marker and refuse; never fall back to raw tree persistence.

**Tests.** Fixture/content scanning, redaction, retention cutoff, unresolved retention, backup exclusion and crash recovery without raw tree.

**Evidence blocker.** No for contract; app backup/export configuration and Android storage behavior need verification.

## 11. APK/source provenance

**Decision.** Release provenance is a signed statement binding source commit, build workflow/run, artifact SHA-256, package, version name/code, signer certificate SHA-256, protocol version and test report digests. Host independently inspects installed APK bytes and signer; Companion self-reported JSON is informational only. M1 will not build/sign an APK.

**Alternatives.** Trust version name/build fields echoed by Companion; trust CI link alone. Rejected because either can be stale/spoofed or not bind installed bytes.

**Security/performance.** Provenance validation is at setup/release and before task admission; artifact pull/hash cost is not per route step. Signing private keys remain outside repo/archive.

**Failure/recovery.** Any missing/mismatch blocks installation/release; preserve existing build and journal state.

**Tests.** Artifact tamper, signer mismatch, commit mismatch, reproducibility metadata and exact installed artifact linkage.

**Evidence blocker.** Yes: designated release pipeline/key custody and installed artifact reader for a non-debuggable production build are not demonstrated.

## 12. Legacy protocol isolation

**Decision.** A dedicated v1 action/component and result provider handle only `tessera.fast-path/1`. Parse/version gate occurs before payload dispatch; unknown/missing versions are rejected and never forwarded to v2.4/v2.5/legacy. Legacy endpoints remain byte-for-byte isolated until separately retired. v1 uses canonical field names only; no aliases.

**Alternatives.** Add v1 branches to existing receiver; default absent versions to legacy; accept aliases. Rejected due downgrade and parser ambiguity.

**Security/performance.** Separate action/component plus closed schema reduces accidental cross-version dispatch. `DUMP` ACL remains broad among shell callers.

**Failure/recovery.** Unknown version is terminal `REJECTED` with no lease acquisition, registry write, journal action boundary, or action. Malformed/duplicate JSON keys fail before routing.

**Tests.** Missing/unknown/old IDs, duplicate keys, each legacy message shape sent to v1, and v1 sent to legacy adapter all fail closed.

**Evidence blocker.** Yes: manifest/receiver routing and legacy isolation require emulator verification.

## Overall M1 disposition

Decisions are closed as a proposal. The unresolved blockers are evidentiary, not permission requests: anti-rollback/trusted time, caller identity, real durability, independent verifier, actual IPC semantics, signer/build pipeline and Android runtime behavior. Contract harnesses may proceed offline; APK release and live execution remain NO-GO.

## M2 host integration clarification (applies to decision 4 and the IPC contract)

**Decision.** For a first v1 implementation, `route.status` carries bounded Companion journal events and both page and full-head sequence/digest values. Host reconciliation starts from its last accepted `(sequence,digest)` and only advances over contiguous, correctly bound, correctly hashed events. Any mismatch is recovery-required. This is a contract completion under the unchanged `tessera.fast-path/1` identifier; no deployed v1 implementation exists to preserve an incompatible wire shape.

**Alternatives.** Trust only `journalSequence` and terminal summary; poll one step at a time; change the protocol identifier before implementation. The summary cannot detect a gap or digest fork. Per-step polling adds host round trips and changes Companion ownership of the local route. A breaking identifier is unnecessary before a v1 client is deployed, but would be required if this contract had shipped.

**Security implications.** Event hashes provide tamper-evidence and ordering consistency, not authentication of Companion or caller identity. Host must compare all correlation dimensions and keep Companion authoritative. A malicious privileged shell caller remains within the stated trust limitation.

**Performance implications.** Status pages are bounded to 256 events and do not add per-step round trips to execution. Larger recovery journals require explicit read-only pagination.

**Failure/recovery behavior.** A page that cannot continue the mirror's exact head, contains a digest/correlation error, rolls the head backward, or conflicts with a known terminal result makes the task `RECOVERY_REQUIRED`. Never infer no dispatch from absent host events and never redispatch.

**Required tests.** Empty page, contiguous page, pagination, sequence gap, digest mismatch, wrong task/request/session/owner/device, head rollback, contradictory terminal outcome, stale response, and host/Companion disagreement.

**Evidence blocker.** Yes for release: Kotlin canonicalization, SQLite transaction ordering, crash recovery, and API response limits require Companion implementation and emulator evidence. The host fake harness is not evidence of those guarantees.
