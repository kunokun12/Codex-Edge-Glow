# Companion Fast Path v1 — Implementation Contract for M2

Status: handoff contract only. No APK code is implemented in this repository or in the Companion repository. All signatures, API access behavior, storage durability and process boundaries below are proposals that require the stated tests.

## Required Kotlin boundaries

The Companion implementation should expose interfaces equivalent to:

```kotlin
interface FastPathTransportHandler {
    fun accept(encodedEnvelope: String, callerUid: Int, device: DeviceBinding): Ack
    fun readResult(requestId: UUID, taskId: UUID, sessionId: UUID, ownerId: UUID): ProtocolResponse
}
interface StrictV1Codec {
    fun decodeRejectingDuplicateKeys(utf8: ByteArray): ProtocolRequest
    fun validateExactSchema(request: ProtocolRequest): ProtocolRequest
    fun encodeCanonical(response: ProtocolResponse): ByteArray
}
interface PromotionAuthorityVerifier {
    fun verifyPromotion(record: PromotionRecord): AuthorityDecision
    fun verifyRevocation(record: RevocationRecord): AuthorityDecision
}
interface RouteRegistry {
    fun findPromoted(routeId: String, version: Int, fingerprint: Sha256): PromotedRoute?
    fun applyPromotion(record: PromotionRecord): RegistryResult
    fun applyRevocation(record: RevocationRecord): RegistryResult
}
interface ExecutionStore {
    fun acquireLease(tuple: CorrelationTuple, nowElapsedMs: Long): LeaseResult
    fun commitDispatchPossible(tuple: CorrelationTuple, stepId: String, expectedSequence: Long): JournalResult
    fun recordActionReceipt(tuple: CorrelationTuple, stepId: String, receipt: ActionReceipt): JournalResult
    fun recordObservation(tuple: CorrelationTuple, stepId: String, digest: Sha256, state: StepState): JournalResult
    fun requestCancellation(tuple: CorrelationTuple): CancelResult
    fun recover(tuple: CorrelationTuple): RecoveryResult
}
interface LocalRouteExecutor {
    suspend fun execute(route: PromotedRoute, tuple: CorrelationTuple, deadlineElapsedMs: Long): ExecutionResult
}
interface AccessibilityObserver {
    suspend fun capture(expectedPackage: String, expectedWindowId: Int?, maxNodes: Int, maxDurationMs: Int): FreshSnapshot
}
interface IndependentDestinationVerifier {
    suspend fun verify(expectedPackage: String, expectedWindowId: Int, expectedFingerprint: Sha256, maxAgeMs: Int): VerificationResult
}
```

The names illustrate boundaries; implementation may use idiomatic types while preserving exact wire JSON and behavior. No HTTP listener or unauthenticated local socket is introduced.

## Receiver/provider boundary

- `FastPathV1Receiver` is an explicit exported component, protected by `android.permission.DUMP`, action `com.codexedge.glow.FAST_PATH_V1`, package `com.codexedge.glow`. It accepts only the single `envelope_b64` string extra and rejects any unknown extras, oversized payload, malformed base64, duplicate JSON names, absent/unknown protocol version, or invalid schema before any write.
- Android's delivered caller UID is checked as the shell UID expected for `adb shell`; reject other UIDs. This establishes shell class only, not Tessera process/user identity. Host checks ADB serial; Companion cannot independently authenticate that serial. Do not treat request `ownerId` as an authenticated principal.
- A read-only exported `FastPathResultProvider` at `content://com.codexedge.glow.fastpath/v1` is protected by `DUMP`. It accepts a fixed read method and exact UUID tuple; URI path and query must not permit arbitrary file access. It returns a bounded canonical JSON response, not a path or raw snapshot file.
- Mutating operation idempotency is persisted before acknowledgement. If receiver delivery result or provider read times out, caller status-checks the same tuple; it never repeats the mutation.
- The v1 receiver is separate from existing v2.4/v2.5/legacy components. Protocol parsing happens before any handler selection. Missing/unknown/old versions are terminal rejection and never call a legacy branch.

## Registry contract

- Use separate app-private schema/version, not SharedPreferences booleans. Suggested tables: `registry_meta(singleton, schema_version, highest_epoch, keyset_version, last_seen_wall_ms)`, `routes(route_id, route_version, fingerprint, canonical_definition, lifecycle, authority_key_id, registry_epoch, expires_at_wall_ms, revoked_at_wall_ms)`, and `registry_events(event_id, epoch, operation, route_id, fingerprint, record_digest, created_at_wall_ms)`.
- Promotion/revoke signature input is canonical JSON with `signature` omitted. Authority verification uses only pinned public keys from the signed Companion package/keyset; no trust of a key supplied solely in the request. Signature algorithm proposal: RSA-PSS SHA-256, 3072-bit key, salt length 32, subject to API 26 provider test. If provider unsupported, reject; do not silently downgrade algorithm.
- In one durable transaction: validate signature, key validity, issue/expiry, route fingerprint, lifecycle and strictly increasing epoch; persist route/tombstone plus audit event; commit before responding. A revoked `(routeId, version, fingerprint)` can never return to PROMOTED. New content requires new version and signed promotion.
- Key rotation requires root-authorized keyset in a newer signed APK, explicit not-before/not-after and retirement epoch. No unsigned network keyset update. Private keys and key material stay out of source, fixtures, logs and archives.
- Execution request must compare the supplied route definition byte-for-byte at canonical form with a local PROMOTED, unexpired, non-revoked entry and exact installed app constraint.
- App-data restore may rewind `highest_epoch` and tombstones. The current platform evidence does not establish a trusted monotonic counter. Detect known regressions and fail closed; do not claim complete rollback resistance until tested or backed by a stronger trusted primitive.

## Task lease and step journal

- Companion SQLite is authoritative for route task lease and per-step action history. Use serialized transactions and `PRAGMA synchronous=FULL`; database open/restore/integrity failures disable execution. A process-local mutex is an optimization; DB uniqueness/transactions are cross-process authority.
- Lease key is the full device/task/request/session/owner tuple. `SystemClock.elapsedRealtime()` is authoritative in a boot. Acquire in the same task-start transaction; idle expiry 30 s, internal heartbeat <=5 s, total task deadline <=120 s. No status/cancel/read operation renews lease.
- Task/step event transitions: `PREPARED`, `OBSERVED`, `MATCHED`, `JOURNALED`, `DISPATCH_POSSIBLE`, `ACTION_ACCEPTED|ACTION_REJECTED|ACTION_UNCERTAIN`, `OBSERVED_NEXT`, `STEP_VERIFIED`, then final verification. Sequence is monotonic per task; events are append-only and digest-linked.
- `DISPATCH_POSSIBLE` is committed in a FULL durability transaction after validating current owner, lease, expected window and cancellation state. Only after successful return may `performAction` be called once. A journal exception yields refusal with zero action calls.
- Cancellation and dispatch boundary serialize on the same DB transaction/lock. Cancellation first => no action. Dispatch marker first => recovery-required, stop all later steps. Do not clear or roll back dispatch markers.
- Host SQLite is a request/audit mirror only. Store task/request mapping, last received Companion sequence and event digest, terminal summary and reconciliation code. Never infer “not dispatched” from a missing host mirror. Any gap, digest mismatch or contradictory terminal state becomes recovery-required.
- Recovery after process death enumerates nonterminal Companion tasks. Any latest state at/after `DISPATCH_POSSIBLE` forbids re-execution. Recovery may collect independent read-only destination evidence and record reconciliation, but no automatic action replay.

## Local accessibility execution

- Before route start and every step verify installed package name, signer digest and exact version name/code, current accessibility root package, expected same-window policy and lease tuple.
- Capture <=4096 nodes/250 ms, stable window before/after, no truncation/error, <=2500 ms old by local monotonic time. Do not reuse a prior step's tree.
- Match exact selector kind/value; only visible, enabled, non-password controls. Collapse matching descendants to unique clickable actionable ancestor. Require exactly one. Refresh the node immediately before dispatch and recheck package/window, cancellation and lease.
- Step action is one `ACTION_CLICK`; no coordinates, retries, text entry, gesture fallback or inferred selector. Observe expected-after before advancing. Route limits 1..32 steps, <=120 s total.
- Stop at first timeout, ambiguity, package/window drift, stale/incomplete tree, cancellation, failed journal, unexpected state or uncertain outcome. No later step is attempted.

## Verification and evidence

- Verification must be implemented in a distinct read-only process/build or on the host through an independently maintained observation provider. It must not consume executor status/action receipt as proof and must not share the executor's in-memory snapshot as the destination observation.
- Return fresh expected package/window/destination fingerprint, observation time/age, independence class and observer build digest. The host accepts `VERIFIED_SUCCESS` only after this correlated result. If separate code/build cannot be demonstrated, return `UNAVAILABLE` and block release.
- Snapshot capture is ephemeral. Persist only schema version, digest, timing/completeness summary and safe state fingerprint; never persist raw nodes or free-form labels.

## Exact JSON/error mapping

Implement the request/result schemas in `docs/fast-path-v1/protocol.schema.json`. `route.execute` must keep the M0 `route.result` response shape; do not accept a generic result for another operation. Lifecycle/control operations use their typed `.result` message types. No field aliases. Error mappings include:

| Condition | Status / reason |
|---|---|
| Missing/unknown/old protocol; duplicate JSON member | `REJECTED / protocol-version-required`, `unsupported-protocol-version`, or `duplicate-json-key` |
| Wrong owner/task/session/device tuple | `REJECTED / correlation-mismatch` or `device-binding-mismatch` |
| Invalid/forged authority signature, epoch regression, revoked/expired route | `REFUSED / promotion-signature-invalid`, `registry-epoch-stale`, `route-revoked`, `route-promotion-expired` |
| Package/version/signer/window mismatch | `REFUSED / app-identity-drift`, `package-mismatch`, `window-mismatch` |
| Stale/incomplete snapshot or target count != 1 | `REFUSED / snapshot-stale`, `snapshot-incomplete`, `target-ambiguous` |
| Journal failure before boundary | `REFUSED / journal-write-failed`, zero action attempts |
| Crash/timeout/lease loss/cancel after dispatch boundary | `RECOVERY_REQUIRED / dispatch-outcome-uncertain` or `cancelled-after-dispatch-boundary`, no retry |
| Host/Companion journal mismatch | `RECOVERY_REQUIRED / journal-disagreement` |
| Status too old | `STALE / status-stale` |
| Destination evidence absent/stale/independent=false | `RECOVERY_REQUIRED / destination-unverified`; never success |

`reasonCode` values are stable safe codes only, never UI text or exception details. Any unrecognized exception maps to `UNKNOWN` plus recovery when dispatch may have occurred.

## M2 host integration clarification: event page and implementation boundary

The host harness now treats this document's Companion journal as the sole per-step authority and host SQLite as a task-level mirror. The existing `route.status` result summary was insufficient to implement the promised sequence/digest comparison. The frozen v1 schema has consequently been completed with bounded `events`, `fromSequence`, `throughSequence`, `journalHeadSequence`, `journalHeadDigest`, and `hasMore` fields. Event digest bytes are defined in `PROTOCOL.md`; Kotlin must match the shared canonical vectors and reject gaps, mismatched event correlation, digest disagreement, head rollback, or contradictory terminal states.

M2 host code is an injection-only integration harness. It does not supply a live ADB executor, a result-provider implementation, a production route registry, SQLite persistence, or Companion authentication. Host route promotion verification and independent destination verification are mandatory injected authorities; returning `true` from a test fake is not evidence of production trust. The host mirror must never override Companion's lease, dispatch journal, or recovery state. This clarification is required for the first v1 implementation and remains under the same protocol identifier because no deployed v1 client exists.
