# Tessera Fast Path v1 — M1 Test, Performance and Release Plan

## Automated gates

1. **Shared fixtures and host contract harness:** run `node --test test/fast-path-v1/contract.test.mjs`. All Node/Kotlin implementations load the same JSON under `test/fixtures/fast-path-v1/`; deterministic `nowEpochMs`, fixed UUIDs and fake verifier/providers only. Check strict schema, expected status/reason/action count, correlation, promotion, fingerprint, lease, cancellation, journal and independent verification. Duplicate key input is stored as raw JSON text so parsers cannot collapse it before validation.
2. **Kotlin unit tests:** consume same fixtures/vectors using `FastPathV1ContractTest.kt`; verify strict parser, canonical fingerprints, policy/state transitions, DB transactions, crash recovery and false-positive rejection. No receiver broadcasts or accessibility action calls.
3. **Node host tests:** serialization, exact schemas, UUID tuple, fixture replay semantics, route selection only from promoted registry, timeouts/no retry, status freshness, host/Companion journal reconciliation, fake signature/observer providers, disabled production composition.
4. **Emulator with actual Companion APK:** only after Kotlin/host gates and source implementation. Exact fixed route, local multi-step, wrong package/window, stale/truncated tree, ambiguous target, journal failures, crashes on each boundary, duplicate request, concurrent task, cancellation races, revoke/expiry, app signer/version drift, status replay, protocol downgrade, process restart, provider ACL. Capture signed APK/test app hashes, logs and results; never transfer this gate to a real phone.
5. **Signed build provenance:** tie source commit, workflow, APK SHA-256, signer certificate digest, package/version/protocol and reports; independently inspect installed bytes.
6. **Separately authorized real-device benchmark:** distinct authorization after release review. This M1 task does not authorize it.

## Proposed measurable targets (not measurements)

| Metric | Target for emulator acceptance | Measurement |
|---|---:|---|
| Host-to-Companion command dispatch + durable acknowledgement | p95 <= 250 ms, p99 <= 500 ms | Time ADB process start to receiver durable ack; report separately from route work. |
| One local observation + unique selector decision | p95 <= 200 ms; hard bound 250 ms traversal | Companion elapsed-realtime timestamps around capture/validation. |
| One action invocation to observed expected-after state | p95 <= 1000 ms; timeout 3000 ms | Per-step monotonic timestamps; do not poll by host. |
| Known 3-step route end-to-end | p95 <= 5000 ms, p99 <= 8000 ms | Include admission, all local observations/actions and independent destination verify; no host polling in the critical path. |
| Cancellation recognition | <= 250 ms before dispatch transaction; after boundary no next step within 250 ms | Barrier-controlled executor tests and emulator concurrency test. |
| Recovery status after process restart | p95 <= 1000 ms after Companion process is available | Reopen DB, validate journal and lease, return exact terminal/recovery state. |

Targets are acceptance goals, not measured results or guarantees. Correctness/safety gates outrank speed.

## Baseline collection plan

M1 records no new performance measurement. Before M2 optimization, use the existing stored 3-step benchmark history only as a historical host-orchestrated reference and preserve its provenance. On the same emulator image/device API, package build, route and test data, collect 30 warmups then >=100 measured iterations. Record p50/p95/p99, per-stage monotonic timestamps, route step count, nodes visited, ADB process count, IPC round trips, action attempts, verification result and device temperature/state. Compare identical known route and identical independent verification. Never compare a one-step and three-step route as if equivalent. No real-device benchmark without separate authorization.

## Kotlin harness status

The Kotlin contract runner is a source-compatible adapter skeleton. `kotlinc` and Gradle are unavailable in this workspace, and no Companion test source was edited. Kotlin tests are therefore **not run** and must not be reported passed.

## Full suite

Run focused contract tests, then `npm test`. The package script is `node --test`; all tests must use fake providers/disposable databases and must leave `skills.sqlite` unchanged. Record exit status and test counts in `M1-REPORT.md`.

## M2 host integration harness

Run `node --test test/fast-path-v1/contract.test.mjs test/fast-path-v1/host-integration.test.mjs` for the shared M1 fixture/schema checks plus injection-only host tests. The host tests use deterministic IDs/clocks, fake registries, fake transport callbacks and an in-memory task mirror. They assert that command construction is inert, transport injection is required, one mutating request is sent at most once, uncertain sends never retry, promotion/revocation/fingerprint and correlation checks fail closed, typed cancellation respects the dispatch boundary, status age and journal pages reconcile strictly, destination evidence is independently bound, and the production gate remains disabled. This gate does not call ADB, a receiver, a provider, SQLite production storage, or Android accessibility.
