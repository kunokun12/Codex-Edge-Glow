# Tessera Fast Path v1 Protocol — M1 Closed Contract

**Protocol ID remains `tessera.fast-path/1`.** No Companion build currently implements it. M0 defined the base execution messages; M1 completes the pre-deployment contract with route lifecycle, status, evidence and verification operations. This is contract completion before any deployed v1 client, not a protocol-ID change. `protocol.schema.json` is the machine-readable schema; objects are closed and unknown/missing members fail.

## Transport envelope and security boundary

Proposed transport is described in `DECISIONS.md` and `ARCHITECTURE.md`: explicit pinned ADB shell broadcast to `com.codexedge.glow/.FastPathV1Receiver`, action `com.codexedge.glow.FAST_PATH_V1`, protected by `android.permission.DUMP`; `envelope_b64` contains base64url UTF-8 JSON, maximum decoded size 65536 bytes. Receiver returns a bounded acknowledgement and commits durable operation state. A DUMP-protected read-only provider at `content://com.codexedge.glow.fastpath/v1` reads a result by `requestId` and exact task/session/owner tuple. Provider response is canonical UTF-8 JSON, max 1 MiB. There is no shared result filename. These Android component details are a proposal pending emulator/API validation.

Transport timeout after command invocation is `UNKNOWN`/`RECOVERY_REQUIRED`; callers MUST NOT resend a mutating operation. They may use a new `route.status` read request to inspect the original task. `route.status`, `evidence.capture`, and `route.verify` are read-only. Duplicate JSON members are rejected before schema validation.

`android.permission.DUMP` identifies a privileged shell caller, not a unique host process/person. Host ADB code verifies the selected serial is the pinned connected target; the Companion app cannot independently authenticate the ADB serial and only echoes it as correlation. Companion can inspect its own installed package/version/signer locally. UUIDs, device serial, package names and hashes do not authenticate a caller/device. Signed promotion records authenticate the offline route authority only. No cryptographic caller identity is asserted.

## Common request envelope

Every request has exactly the following top-level members:

```json
{
  "protocolVersion": "tessera.fast-path/1",
  "messageType": "route.execute",
  "requestId": "lowercase UUIDv4",
  "taskId": "lowercase UUIDv4",
  "sessionId": "lowercase UUIDv4",
  "ownerId": "lowercase UUIDv4",
  "deviceBinding": {
    "deviceSerial": "opaque pinned serial",
    "companionPackage": "com.codexedge.glow",
    "companionVersionCode": 10,
    "signerCertificateSha256": "64 lowercase hex"
  },
  "expiresAtEpochMs": 1790000000000,
  "payload": {}
}
```

All four IDs are independently generated and required. They are identifiers, not credentials. Mutating an existing request ID's body or tuple is rejected. A duplicate mutating request returns only the persisted prior outcome and MUST NOT repeat an action or registry write. `expiresAtEpochMs` is checked at receiver admission, converted to local elapsed-realtime deadline and capped to five minutes. It does not compare host and device monotonic clocks.

## Request message types and exact payloads

| `messageType` | Exact `payload` | Effect |
|---|---|---|
| `route.promote` | `{promotionRecord}`. Record exactly `{authorityKeyId, registryEpoch, issuedAtEpochMs, expiresAtEpochMs, routeId, routeVersion, routeFingerprint, routeDefinition, signature}`. | Verify authority signature, route hash, expiry and epoch; commit promoted entry atomically. Lifecycle must already be reviewed in the signed assertion; caller cannot set it. |
| `route.revoke` | `{revocationRecord}`. Record exactly `{authorityKeyId, registryEpoch, issuedAtEpochMs, routeId, routeVersion, routeFingerprint, reasonCode, signature}`. | Verify signed tombstone and strictly newer epoch; commit revoke before acknowledgement. |
| `route.execute` | `{routeId, routeVersion, routeFingerprint, routeDefinition}`. | Acquire Companion lease, compare exact full definition to locally promoted entry, then execute bounded route locally. Definition shape is in schema. |
| `route.cancel` | `{reason}` (stable non-sensitive kebab code). | Owner-scoped cancellation of exact active task. No lease renewal. |
| `route.status` | `{routeId, routeVersion, routeFingerprint, maxAgeMs, afterSequence, afterDigest, maxEvents}` (`maxAgeMs 1..2500`, `maxEvents 1..256`). | Read-only task/registry status; stale live lease observations return `STALE`. Historical terminal journal state may be returned as historical, never as a fresh active lease. |
| `evidence.capture` | `{expectedPackage, expectedWindowId, maxAgeMs, maxNodes}` (`maxAgeMs 1..2500`, `maxNodes 1..4096`). | Read-only bounded snapshot capture. No route refresh/write or promotion. |
| `route.verify` | `{executionRequestId, routeId, routeVersion, routeFingerprint, expectedDestinationFingerprint, expectedPackage, expectedWindowId, maxAgeMs}`. | Invoke the separate read-only verifier; action receipt/result is not accepted as evidence. |

`protocol.schema.json` defines exact nested route/promotion/revocation shapes. Protocol version is exact. Missing, malformed, `tessera.companion.fast-path.v2.5`, unknown and future versions are `REJECTED`; do not forward to or fall back into legacy handlers. No aliases (`taskUuid`, `ownerHash`, `ownerIdHash`, `protocol_id`) are accepted.

## Promotion authority and canonical route

Route fingerprint is SHA-256 lowercase hex of UTF-8 canonical JSON for `{fingerprintVersion:"tessera.route-canonical/1",routeId,routeVersion,routeDefinition}`. Canonicalization: NFC strings; object keys sorted by Unicode scalar/code point; no insignificant whitespace; standard JSON escaping for quote/backslash/control characters; other Unicode encoded as UTF-8; integers only; arrays ordered. Reject duplicate keys, floats, out-of-range integers, invalid UTF-8 and unpaired surrogate input. Object key order cannot change the digest. Changing any route field or array order does.

Promotion signature covers the same canonical record excluding `signature` and also binds `authorityKeyId`, registry epoch and issue/expiry. Revocation signature covers its entire record excluding `signature`. M1 proposes RSA-PSS/SHA-256 with 3072-bit authority keys for Android API 26 compatibility, pending actual provider/API verification. Trust anchors are shipped in verified Companion APK; private root keys remain offline. Rotation is a root-signed keyset in a higher-version APK with overlap and retirement epoch. Signed promotions expire in 24 hours; offline execution is permitted only while a locally stored promotion is unexpired and not locally revoked. A remote revocation cannot reach an offline Companion until delivered. Rollback across app-data restore is not cryptographically prevented by the proposal and blocks release until solved/tested.

The host may send a route body but Companion must require exact match to its own signed/promoted registry. A route's fingerprint, host-supplied lifecycle/status, or successful host review is not promotion proof.

## Response envelopes

Every M1 typed response has exactly `{protocolVersion, messageType, requestId, taskId, sessionId, ownerId, deviceBinding, status, reasonCode, observedAtEpochMs, payload}`. `messageType` pairs exactly with its request: `route.execute` uses the frozen M0 `route.result`; other M1 operations use the corresponding request type plus `.result` (for example `route.promote.result`). The old generic `snapshot.result` schema branch is retained only to validate M0 fixtures and is not a live M1 response. `deviceBinding` and all IDs echo the original request exactly. Response payloads are operation-specific and closed in `protocol.schema.json`.

- `route.promote.result.payload`: `{routeId, routeVersion, routeFingerprint, lifecycle, registryEpoch, authorityKeyId, expiresAtEpochMs}`. `lifecycle` is `PROMOTED|REFUSED`.
- `route.revoke.result.payload`: `{routeId, routeVersion, routeFingerprint, lifecycle, registryEpoch, reasonCode}`. `lifecycle` is `REVOKED|REFUSED`.
- `route.result` (frozen M0 execution response) carries its original exact `lease`, `execution`, `snapshot`, `verification` and `provenance` fields. Its schema requires independent `VERIFIED` evidence for outer success. The M1 host accepts it only as the result of `route.execute`. `route.execute.result` is not a v1 message.
- Execution result payload (represented by the frozen M0 `execution` field): `{taskState, currentStepId, completedStepCount, journalSequence, dispatchPossible, actionReceipt, verificationStatus}`. `taskState` is `PREPARED|RUNNING|VERIFYING|VERIFIED_SUCCESS|REFUSED|CANCELLED|RECOVERY_REQUIRED`; receipt `ACCEPTED|REJECTED|UNCERTAIN|null`; verification `VERIFIED|UNVERIFIED|OTHER_DESTINATION|UNAVAILABLE|null`.
- `route.cancel.result.payload`: `{taskState, stopAccepted, stopAfterCurrentStep, journalSequence, dispatchPossible}`.
- `route.status.result.payload`: `{taskState, currentStepId, completedStepCount, journalSequence, dispatchPossible, leaseState, leaseExpiresAtElapsedRealtimeMs, observedAtElapsedRealtimeMs, executionRequestId, fromSequence, throughSequence, journalHeadSequence, journalHeadDigest, events, hasMore}`. `events` is a contiguous page of <=256 exact journal events. Each event binds task/request/session/owner/device, sequence, event type, step, task state, dispatchPossible, previous digest and event digest. Digest is lowercase SHA-256 of canonical JSON over the event fields excluding `eventDigest`. Initial sequence is 0 with a 64-zero digest. Request `afterSequence/afterDigest` must exactly match the host mirror head; pages continue without gaps. Monotonic fields are only meaningful within the same boot/device and not compared with host clocks.
- `evidence.capture.result.payload`: `{snapshot, producer}` using the bounded Companion snapshot v3 shape (schema 3, accessibility source, stable window before/after, traversal counts/errors, at most 4096 nodes). It is ephemeral and must not be journaled raw.
- `route.verify.result.payload`: `{status, packageName, windowId, observedAtEpochMs, ageMs, destinationFingerprint, independenceClass, observerBuildSha256}`. `independenceClass` is `separate-observer-process|host-independent-reader`.

Common statuses: `PREPARED`, `ACCEPTED`, `RUNNING`, `PROMOTED`, `REVOKED`, `VERIFIED_SUCCESS`, `REFUSED`, `REJECTED`, `CANCELLED`, `RECOVERY_REQUIRED`, `UNKNOWN`, `STALE`. Unknown status/reason or malformed response is uncertainty, not success. `VERIFIED_SUCCESS` requires a fresh `route.verify.result` with `status=VERIFIED`, expected package/window/fingerprint, age <=2500 ms, independently approved observer class and matching observer artifact provenance. The response schema prevents a route executor response from claiming success with unverified status; host contract additionally requires separate verifier evidence.

## Execution, leases and failure rules

Companion owns a 30-second idle lease, internally renewed at <=5-second intervals, with a 120-second maximum route execution. It uses elapsed realtime. Only `route.execute` acquires; reads/cancel never renew. Each exact owner/task/session/device operation checks active lease.

For each step, capture fresh complete state; check package/window and expected-before; resolve exactly one visible/enabled actionable selector; atomically check cancellation/lease and commit `DISPATCH_POSSIBLE`; issue at most one action; observe expected-after. A failed journal write means no action. Once dispatch possibility commits, timeout, process death, transport loss, missing result, cancellation, or host/Companion journal disagreement cannot lead to a retry. Stop remaining steps and report `RECOVERY_REQUIRED`.

Cancellation that commits before the dispatch transaction returns `CANCELLED` with zero action. Cancellation after the boundary stops later steps and leaves the current step uncertain. Expired leases stop new actions but do not make a step retryable.

## Freshness, bounds and provenance

Snapshots are age-checked against the Companion's own monotonic capture time, <=2500 ms, traversal <=4096 nodes/250 ms, complete, stable, no traversal errors or truncation. Each step observes anew; no stale snapshot reuse. Package and same-window IDs must match expected route constraints.

Route provenance requires authority key ID and signed promotion record. APK provenance binds source commit, CI workflow, artifact digest, signer certificate digest, package/version/protocol, and test report digests. Producer JSON alone is never authentication. See `COMPANION-IMPLEMENTATION-CONTRACT.md` for exact receiver/result-provider behavior and M2 evidence gates.

## M2 host contract clarification: status journal pages

The M2 host harness found that the earlier status result summary (`journalSequence`) did not carry enough information for the required strict host/Companion reconciliation. Before any v1 client ships, `route.status` therefore uses the exact bounded event page now present in the schema. This completes `tessera.fast-path/1` before an implementation is deployed; it does not introduce a second version or accept older status shapes.

Each event's `eventDigest` is lowercase SHA-256 of UTF-8 `canonicalJson(eventWithoutEventDigest)`. `eventWithoutEventDigest` contains precisely the schema fields `sequence`, `taskId`, `requestId`, `sessionId`, `ownerId`, `deviceSerial`, `eventType`, `stepId`, `taskState`, `dispatchPossible`, and `previousDigest`. The first event has sequence 1 and `previousDigest` of 64 zeroes. Each later event uses the preceding event digest. `throughSequence` is the final sequence in this page (or `afterSequence` for an empty page); `journalHeadSequence` and `journalHeadDigest` identify Companion's current complete journal head and may be ahead of the page when `hasMore` is true. The host sends its last reconciled `(afterSequence, afterDigest)` and accepts only contiguous events from that exact point. A missing page, mismatched digest, invalid event binding, or head regression makes the task `RECOVERY_REQUIRED`; an empty page does not prove that an action was not dispatched.

`observedAtEpochMs` on the response envelope timestamps the status result and is checked against the request's `maxAgeMs`. Lease elapsed-realtime fields remain Companion-local and must not be compared to host monotonic time. Pagination is read-only and explicit; it does not renew a lease or replay a mutation.
