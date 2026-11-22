# Tessera Fast Path v1 — Architecture and M1 Decisions

Status: architecture proposal plus host contract harness. Protocol remains `tessera.fast-path/1`. No v1 implementation is present. M1 adds lifecycle/control message types before any deployed v1 client; see `DECISIONS.md` for alternatives and evidence blockers.

## Execution profiles

- **Fixed benchmark:** the single approved `benchmark.open_guide` route on `dev.androidroute.benchmark`, with exact package signer/version and deterministic origin/destination. It measures contract correctness and known-route latency; it does not establish general learning safety.
- **Learned routes:** candidates progress `DISCOVERED -> REVIEWED -> PROMOTED -> REVOKED`. A reviewer verifies origin, each ordered transition and final destination. An offline route authority signs the immutable promoted definition. Companion stores and verifies the promotion. Existing learned route records remain candidates and are never silently promoted.

## Components and authority

| Component | Responsibility and authority |
|---|---|
| Host planner | Resolves intent to a locally known promoted route, binds IDs/device, submits one `route.execute`, monitors via explicit read-only status. It cannot promote routes by assertion and cannot decide dispatch outcome. |
| Promotion authority | Offline private signing key issues promotion/revocation records over exact canonical route references. Public trust anchors are delivered only in a verified Companion APK/keyset update. Keys never enter this repository. |
| Companion registry | App-private registry is authoritative for promotion/revocation, registry epoch, expiry and route definition. Promotion signature is verified before commit. Hash alone is not trust. Host DB is a candidate/source mirror only. |
| Companion executor | Validates schema, registry, package/signer/version, task lease, fresh complete accessibility state, selector uniqueness and cancellation. Executes ordered steps locally. |
| Companion journal | Authoritative record of action possibility and per-step results. Commit `DISPATCH_POSSIBLE` durably before each action. Companion owns recovery decisions. |
| Host journal | Existing SQLite task journal remains authoritative for host allocation/audit, with mirrored Companion sequence/event digest. It never overrides device-side dispatch evidence. Disagreement is recovery-required. |
| Independent verifier | Separate read-only observer process/build and channel; no access to action receipt as destination evidence. Its independence is not established by a second call to the same accessibility service. |

## One-request execution lifecycle

`NEW -> PREPARED -> RUNNING -> VERIFYING -> VERIFIED_SUCCESS`; safe exits are `REFUSED`, `REJECTED`, or pre-boundary `CANCELLED`; uncertainty after a dispatch boundary is `RECOVERY_REQUIRED`.

Companion acquires one exclusive lease for `(deviceSerial, taskId, requestId, sessionId, ownerId)`. Lease authority is Companion; host execution lease is a duplicate-work optimization only. Lease uses `elapsedRealtime`, 30-second idle TTL renewed internally at <=5 seconds, and a 120-second hard task cap. Host unavailability after accepted `route.execute` does not stop local execution; the executor completes or reaches safe recovery without host messages between steps.

For each step: capture bounded fresh accessibility tree; require complete stable tree and expected package/window; compare expected-before state; resolve exact selector and collapse matching descendants to actionable clickable ancestor; require exactly one enabled/visible target; check lease and cancellation; atomically check cancellation/owner/lease and commit `DISPATCH_POSSIBLE`; invoke at most one accessibility action; record receipt; observe resulting UI; verify exact expected-after. Stop on stale/incomplete observation, multiple/zero targets, app/window change, timeout, journal error, or uncertainty. No coordinates, fuzzy fallback, or automatic retry.

**Crash rule:** before a committed dispatch boundary, no action is permitted. After it, absence of a durable receipt cannot prove whether invocation occurred. Recovery reports `RECOVERY_REQUIRED`; never replay that step. Host/Companion journal disagreement is resolved conservatively using the Companion step journal for possible dispatch, and both records are preserved.

**Cancellation ordering:** cancellation and dispatch boundary compete through one Companion transaction. If cancellation commits first, the action does not run. If `DISPATCH_POSSIBLE` commits first, cancel prevents all later steps and returns recovery-required; it does not roll back the boundary.

Only independent verification of fresh destination evidence can produce `VERIFIED_SUCCESS`. Action acceptance is never task success.

## Route representation, promotion and invalidation

A route definition includes `packageName`, exact app signer/version constraint, origin/destination state predicates, `windowPolicy=same_window`, and 1..32 ordered step definitions. Each step has a stable ID, selector (`text|contentDescription|resourceId`), fixed `uniquenessPolicy=exactly_one_actionable_target`, expected-before and expected-after predicates. No coordinates, free-form user text or secret markers.

Canonical route object is `{fingerprintVersion:"tessera.route-canonical/1",routeId,routeVersion,routeDefinition}`. Fingerprint is SHA-256 of canonical UTF-8 JSON: NFC strings; object keys sorted by Unicode scalar/code point; no insignificant whitespace; JSON quote/backslash/control escaping; non-ASCII UTF-8; safe integers only; arrays ordered; duplicate keys, floats and unpaired surrogates rejected. Existing legacy fingerprints keep their distinct existing algorithm.

Promotion record is signed over canonical JSON without the signature field and contains authority key ID, monotonically increasing registry epoch, issue/expiry time, full route definition and fingerprint. Default promotion validity is 24 hours, enabling bounded offline execution. Revocation is a signed tombstone over exact route/version/fingerprint and higher epoch; Companion persists it and never resurrects that fingerprint. If offline, a device cannot receive a new revocation; expiry bounds exposure but cannot make revocation instantaneous. Key rotation is a root-signed keyset shipped in a verified higher-version APK; old key retirement and overlap are explicit. App data restore/rollback resistance is unproven and blocks release.

v1 pins exact package, signer digest, version name and code. Drift, changed origin/selector/postcondition, ambiguity, stale/incomplete snapshot, package/window change, verification failure, evidence revocation, protocol/build incompatibility or expiry invalidates execution. Revalidation produces a new route version and promotion; no auto-promotion.

## Transport and trust boundary

Proposed transport: explicit pinned-device `adb shell am broadcast` to one exported v1 receiver protected by `android.permission.DUMP`. The envelope is UTF-8 JSON, base64url encoded as one `envelope_b64` extra, <=64 KiB. Receiver validates exact protocol version before payload routing, enqueues work, and returns a correlated acknowledgement. Companion persists outcomes privately; a DUMP-protected read-only ContentProvider returns exact request results/status by request ID and full correlation tuple. Unique request IDs avoid singleton result-file races. Status/cancel are separate explicit commands; no host call occurs per route step.

`DUMP` means privileged Android shell access, not authentication of a particular Tessera process or human. Serial, UUIDs, hashes, package metadata and producer claims are correlation/claims, not caller/device cryptographic identity. Promotion signatures authenticate authority statements only. No security claim is made for caller identity. If ordinary apps can reach the components or result provider, endpoint access is rejected.

## Snapshot, privacy, provenance

Snapshot age and lease deadlines use Companion monotonic time. `expiresAtEpochMs` is checked once at receipt, converted to a local monotonic deadline and capped at five minutes; host/device monotonic clocks are never compared. Wall clocks are diagnostic and admission bounds only. Each route step uses a new capture <=2500 ms old, complete, stable, bounded to 4096 nodes/250 ms, with no truncation/error.

Accessibility trees exist in memory only and are discarded after step verification. Persist route definitions, state fingerprints, evidence digests, safe reason codes and journal state; never raw text trees, screenshots, form values, bounds or coordinates. Resolved journals retain 30 days; unresolved records remain until reconciliation then 30 days. Promotion audit digests retain through active lifetime and 90 days after revocation. App-private deletion is not cryptographic erase.

Release provenance binds source commit, build workflow, artifact SHA-256, signer SHA-256, package/version/protocol and test-report digests. Host inspects installed artifact; Companion producer JSON is not proof. Signing keys and credentials are excluded from repository/archive.

## Fixed benchmark versus learned routes

The benchmark route is a fixed deterministic conformance and latency case with exact app identity and expected destination. It can validate the pipeline and local step loop. General learned routes require discovery, review, independent transition/destination evidence and a signed promotion. Benchmark success cannot auto-promote any learned route or demonstrate safety for arbitrary apps.

## Existing mechanisms to preserve

- Preserve `/root/android-mcp/skills.sqlite`, its `learned_routes` rows and legacy fingerprint algorithm `SHA-256(JSON.stringify({id,package,steps}))` unchanged. M1 only read the saved route and confirmed its three-step legacy fingerprint.
- Preserve the separate semantic UI memory schema, the host task journal/idempotency/recovery invariants, production-disabled feature flag, Companion exact-selector policy and no-retry behavior.
- Add the Companion registry and per-step journal as a new versioned app-private schema; do not migrate old route rows automatically.
- Isolate the v1 receiver/provider from v2.4/v2.5/legacy handlers. Missing, unknown or old protocol IDs are rejected before any mutation.

## M1 non-goals

No Companion source edits, APK builds/installs, services, receivers, snapshots, live actions, database/journal writes, route promotion, GitHub changes or production enablement. See `DECISIONS.md`, `PROTOCOL.md`, and `COMPANION-IMPLEMENTATION-CONTRACT.md` for proposals and blockers.

## M2 host preparation boundary

`ui-intelligence/live/fast-path-v1-host-transport.mjs` prepares the v1 request and inert ADB command descriptor, but accepts the only command executor and result reader as constructor dependencies. The module has no default process, ADB, Android, MCP, or provider capability. One request ID is accepted at most once per transport instance; send/result timeout is uncertain and is never retried.

`ui-intelligence/live/fast-path-v1-host-coordinator.mjs` is a fake-backed host coordinator. The host selects only an unexpired `PROMOTED` route from an injected registry and independently invokes injected promotion/revocation verifiers. It mirrors task-level request/result state only. Companion remains authoritative for leases, cancellation ordering, step journal, action possibility and recovery. Host completion additionally requires a separate injected destination verifier with exact expected package/fingerprint, fresh evidence, an independence claim, and observer build digest. These callback checks shape the future boundary; fake implementations do not provide production authentication.

`production-fast-path-v1-host.mjs` reports disabled in all cases until a reviewed live composition exists. Setting `TESSERA_FAST_PATH_ENABLED=1` does not create or activate a transport. No production SQLite adapter is introduced by M2.
