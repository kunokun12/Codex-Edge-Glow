# Tessera Fast Path v1 — M2 Host Contract and Integration Preparation

**Result:** Host-side integration preparation complete; live execution remains disabled. The protocol identifier is unchanged: `tessera.fast-path/1`. No Companion source, APK, Android device, receiver, service, or production route/journal was used.

## Implementation summary

- Added an injection-only v1 transport. It validates the closed protocol schema and version, encodes canonical JSON as bounded base64url, constructs the exact explicit receiver command as inert data, and reads results only through an injected provider callback. It has hard timeouts, no default executor, no fallback, and no automatic retry.
- Added a fake-backed host coordinator for promoted-route selection, fingerprint/promotion/revocation checks, request tuple correlation, single-flight device execution, task-level mirror updates, cancellation, status reconciliation, recovery-required handling, and independent destination-evidence checks.
- Added strict event-page reconciliation. Host mirror state cannot establish that a dispatch did not happen. Uncertain send, sequence gap, digest mismatch, wrong event binding, head rollback, or contradictory terminal state blocks retry and requires recovery.
- Added a production gate that always remains disabled because live v1 wiring is unavailable, including when the environment opt-in is set.
- Completed the pre-deployment status journal page fields in the v1 schema. The earlier result summary did not carry enough data to detect journal gaps/digest forks. The exact page and event digest algorithm is now documented. This stays under `/1` because there is no deployed v1 client; any compatibility with a deployed older v1 status shape would require a breaking protocol revision.

## Tests

- Shared schema, fixtures, and route canonicalization vectors: run as part of focused contract suite.
- Focused command: `node --test test/fast-path-v1/contract.test.mjs test/fast-path-v1/host-integration.test.mjs` — **20 passed, 0 failed**.
- Full command: `npm test` (`node --test`) — **484 passed, 0 failed, 0 skipped**.
- Covered strict protocol/version, duplicate JSON key parsing, route fingerprint vectors, response correlation, promotion/revocation, concurrency, cancellation around dispatch, status freshness, event digest/sequence reconciliation, crash/uncertain-send recovery, independent verification, transport timeout/no-retry, and the disabled production gate.
- Tests use deterministic IDs/clocks, in-memory mirror, fake registry/verifiers, and injected transport. No receiver/provider or Android action ran.
- Kotlin, emulator, signed APK provenance, and live-device tests were not run. The Kotlin toolchain and Companion implementation remain unavailable in this host-only milestone.

## Production-data integrity and safety

- `isFastPathEnabled()` returned `false`; the M2 v1 production gate reports disabled for both unset/`0` and `1` opt-in values.
- No M2 code writes `skills.sqlite`; all DB inspection was read-only. The current file SHA-256 is `7892fce5d3d82e3db847f049a0af33fd0124541f6fcd5ded183fc5e4b748996f`; its filesystem modification time predates M2. The M1 report records a different digest (`7892fce5d3d82e3db847f049a0af33fd0124541f6fcd5ded183fc7e5b4e748996f`). This is an inherited integrity-record discrepancy; M2 does not claim those two recorded values match.
- Read-only inspection found `phase-7.6a-benchmark-three-step` in `learned_routes`, package `dev.androidroute.benchmark`, with three saved steps. The long-standing documented legacy fingerprint remains `09cc7dcc82b9105c0f865b549d88a300454d38182e16675e4629957ebaefe4ac`. No route migration or writes were performed.
- No APK build/install, ADB/receiver call, Android action, snapshot refresh, service restart, production fast-path enablement, Companion GitHub edit, or network operation occurred.

## Remaining Companion dependencies

1. Implement and test the strict Kotlin v1 codec, duplicate-key rejection, exact schema, event digest and shared route hash vectors.
2. Implement app-private signed route registry, promotion/revocation authority and rollback policy; demonstrate key rotation and restore behavior.
3. Implement Companion-authoritative lease, serialized cancellation/dispatch boundary, durable per-step journal, pagination and crash recovery.
4. Implement and emulator-validate the explicit receiver and read-only provider, `DUMP` boundary, caller UID behavior, argument/result size limits, timeouts, and legacy isolation.
5. Demonstrate a genuinely independent destination observer and signed source/build/APK provenance. These are not authenticated by host IDs or self-reported Companion claims.
6. Resolve the M1 SQLite checksum record discrepancy by capturing a reviewed baseline before any subsequent production-data integrity claim.

## Readiness decisions

| Decision | M2 result | Reason |
|---|---|---|
| Proceed to Companion implementation | **GO for isolated implementation and offline tests only** | Host interface and v1 schema are precise enough for a non-production implementation; no release trust claims are made. |
| APK release | **NO-GO** | Kotlin implementation, emulator gates, independent verifier evidence, signed provenance and release review are absent. |
| Live execution | **NO-GO** | Production gate is disabled; no live transport is wired; Companion lease/journal and verifier are not implemented or proven. |

## Next milestone

M3 should implement Kotlin strict parsing and shared vectors first, then the additive local route registry and journal behind fake-clock/disposable-store tests. Continue with cancellation/lease/recovery and receiver/provider instrumentation only after those gates pass. Keep the host production gate disabled. Do not build/release an APK or execute live routes until independent verification, provenance, emulator tests, and the database baseline discrepancy are resolved.
