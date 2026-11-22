# Fast Path v1 M4–M6 implementation notes

This document records the Android Companion implementation in this branch and
the release gates that remain closed. The M1 contract and the shared host
canonicalization vectors remain the protocol sources of truth.

## M4 — durable route registry

`FastPathRegistryV1` stores the epoch, route projection, hash-chained registry
events, and request receipts in SQLite with `synchronous=FULL` and serialized
transactions. Promotion/revocation admission, epoch comparison, projection
mutation, event append, and the typed response receipt commit together. Duplicate
request IDs return the original durable result, including a refusal, and a reused
ID with a changed body is refused. Reads recheck route fingerprint, signed record,
expiry, authority epoch range, and the latest route version. Revocation is a
durable tombstone. Database integrity, epoch head, event chain, route projection,
promotion/revocation record digests, and receipt digests are checked on open.
An app-owned atomic marker prevents a missing initialized database from being
silently recreated. Schema migration and downgrade failures stop registry use.

The packaged trust-anchor file is intentionally an empty keyset. No production
RSA-3072 authority public key was supplied by the source artifacts, so the
installed build rejects every promotion and revocation. Tests inject ephemeral
generated key pairs and verify real RSA-PSS signatures; those keys are test-only
and do not authorize production routes. Rotation is represented by authority
epoch ranges in the APK-owned keyset; there is no production keyset to rotate yet.

## M5 — local route engine

`FastPathExecutionEngineV1` compares the request's exact route body with the
promoted registry entry, checks package/version/signer identity, obtains bounded
fresh evidence, matches origin and per-step states, requires one actionable
selector, preserves the original window, journals before dispatch, and calls a
single local action port without an inter-step model interface. Independent
verification must match package, window, destination fingerprint, destination
markers, freshness, independence class, and a pinned observer build digest.
`FastPathAndroidActionPortV1` contains the bounded Accessibility traversal and
single-click adapter; it revalidates the selector and click target before action.

The runtime gate defaults to disabled. The production receiver deliberately
continues to return `UNIMPLEMENTED` for `route.execute`, `evidence.capture`, and
`route.verify`. M1 names `stateFingerprint` and its constraints but does not
specify an Android UI-tree fingerprint producer, and no approved producer or
independent observer artifact/provenance was supplied. The Android adapter
therefore returns no execution evidence without injected providers. Only the
deterministic simulated engine tests exercise a successful multi-step route.
No real Android action was enabled or run.

## M6 — leases, journal, and verification

`FastPathExecutionJournalV1` persists owner-scoped leases, fencing tokens,
request identity, route identity, transitions, action outcomes, and a contiguous
SHA-256 event chain. It commits `DISPATCH_POSSIBLE` before the action port is
called, rejects duplicate request IDs, and treats post-boundary cancellation,
process loss, lease expiry, action exceptions, and missing observations as
uncertain. Status/cancel resolve the original execution using the task/session/
owner/device tuple because each control request has its own request ID. Status
reports inactive nonterminal leases as stale. A successful journal terminal
state requires every route step to be verified and a still-live fenced lease at
the independent-verification boundary.

The journal and engine are not reachable from `route.execute` while the M5
fingerprint and observer gates are absent. M6's persistent-state and fault
injection tests establish the internal state-machine behavior only; they do not
prove Android Accessibility dispatch behavior, process-death behavior on a
physical device, or production observer independence.

## Conformance and release status

The strict receiver parser enforces the supplied closed request schemas and all
shared M1 canonical-route vectors are exercised by the Android test suite. The
result provider is DUMP-protected, read-only, tuple-scoped, and returns atomic
canonical response files or registry receipts. The app never trusts message
supplied authority keys and contains no private route-signing key.

Production remains **not ready** until a production trust anchor, specified and
validated state fingerprint producer, separately built/pinned verification
observer, Companion artifact provenance flow, and Android device/API validation
are available. Do not enable the runtime gate as a release shortcut.
