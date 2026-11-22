# Tessera Fast Path v1 — Milestone M1 Report

Date: 2026-11-21. Scope: architecture decision closure and executable offline contract harness. Work stayed in `/root/android-mcp`. M0 protocol identifier `tessera.fast-path/1` is unchanged. No Companion APK source, build, install, receiver, service or GitHub source was touched.

## Overall result

**M1 complete for design and offline host contract.** All 12 M0 design questions now have explicit decisions, alternatives, security/performance implications, failure behavior, tests and evidence-blocker status. The protocol specification now covers route promotion/revocation, execution, cancellation, status, bounded evidence capture and independent verification, with closed schemas and correlation rules.

These decisions are architecture proposals, not implemented security guarantees. In particular, `DUMP` grants privileged shell access but does not authenticate a specific host process or person. Route signatures authenticate authority records only. Registry app-data rollback and a truly independent verifier remain release blockers.

## Architecture decisions

- Companion owns promoted route registry, revocations, task lease, per-step dispatch journal and recovery. Host SQLite is an allocation/audit mirror; disagreement is recovery-required.
- Lifecycle is `DISCOVERED -> REVIEWED -> PROMOTED -> REVOKED`; signed promotion/revocation records, exact canonical route, epoch and 24-hour promotion expiry. Offline execution is allowed only while a local promotion remains valid; remote revocation cannot reach an offline device until sync.
- Proposed transport is explicit pinned ADB broadcast to a DUMP-protected v1 receiver, with a DUMP-protected read-only result provider. It identifies shell class, not a unique caller. No automatic retries.
- Companion uses a 30-second idle lease, internal <=5-second renewal, 120-second hard task limit; monotonic time. Dispatch boundary commits before one click attempt. Cancellation before commit prevents action; after commit prevents later steps and requires recovery.
- Route fingerprints use `tessera.route-canonical/1`, separate from legacy fingerprints. Exact signer/version/package constraints, snapshot freshness and exact-one target uniqueness are required.
- M1 proposes RSA-PSS/SHA-256 authority signatures with 3072-bit keys, but API 26 provider support must be demonstrated. Private keys are excluded.
- Independent verification must run on a separate read-only observer path; current evidence does not establish that trust boundary.
- Performance goals are proposed acceptance targets only. No performance measurements were made in M1.

## Tests and environment

- Focused shared contract tests: **8 passed, 0 failed** (`node --test test/fast-path-v1/contract.test.mjs`).
- Shared cases: **36 JSON cases** plus canonical JSON/SHA-256 vectors. Added all 12 requested M1 negative cases, preserving M0 cases. Negative fixtures were made self-contained by removing inherited success responses from unrelated failure cases and adding explicit expected snapshot package/window context; names and expected safety outcomes were retained.
- Full repository suite: **472 passed, 0 failed, 0 skipped** (`npm test`).
- Offline JSON schema, fixture outcomes, missing/unknown/downgrade protocol rejection, correlation, operation/result pairing, duplicate-key parser, fake signature verifier, route hash vectors and no-retry boundaries passed.
- Kotlin: **not run**. `kotlinc` and Gradle are unavailable. The Kotlin adapter harness is a source-compatible test skeleton, not a claim of compilation or pass.
- Android/emulator, APK signing/provenance, and real-device tests: **not run**.

## Protected-data integrity and safety

- `skills.sqlite` SHA-256 before and after: `7892fce5d3d82e3db847f049a0af33fd0124541f6fcd5ded183fc7e5b4e748996f` (unchanged).
- Saved route `phase-7.6a-benchmark-three-step`: 3 steps; legacy SHA-256 remains `09cc7dcc82b9105c0f865b549d88a300454d38182e16675e4629957ebaefe4ac` (unchanged).
- `isFastPathEnabled()` returned false after testing.
- Tests used fake providers/disposable data; no production DB/route/journal writes, live Android action, receiver call, snapshot refresh, service restart, APK build/install, or GitHub change occurred.

## Remaining blockers

1. Demonstrate or replace app-data epoch/tombstone rollback exposure and clock-trust assumption.
2. Review the shell/DUMP caller boundary; it does not identify Tessera as a unique caller.
3. Demonstrate actual API 26 crypto/provider and broadcast/provider behavior.
4. Implement and evidence a genuinely independent destination observer/build.
5. Verify Android SQLite durable commit/crash semantics and host/Companion reconciliation.
6. Name the release pipeline/key custodian and installed non-debuggable artifact verifier.

## M2 recommendation and go/no-go

- **Companion implementation:** **GO** for isolated source-level codec/registry/journal/state-machine work with fake-only tests after the blockers above are tracked; no production wiring. **NO-GO** for authenticated promotion, signed release or action-capable rollout until the relevant evidence passes.
- **APK release:** **NO-GO** — provenance pipeline, rollback resistance, IPC review and independent verifier are not demonstrated.
- **Live execution:** **NO-GO** — no live authorization in this milestone and security/runtime gates remain open.
- **M2 plan:** implement the Kotlin harness adapter first; implement strict v1 parsing and route fingerprint vectors; build additive app-private registry/journal with fake-clock/crash tests; implement owner lease, cancellation transaction and recovery; wire exact receiver/provider only after IPC review; add host transport/result adapter with strict operation/result matching; run Kotlin/Node gates. Do not build/install an APK until those gates and provenance review pass.

## Exact work products changed

- Updated: `docs/fast-path-v1/ARCHITECTURE.md`, `DECISIONS.md`, `PROTOCOL.md`, `protocol.schema.json`, `TEST-PLAN.md`, and `MIGRATION.md`.
- Added: `docs/fast-path-v1/M1-REPORT.md` and `COMPANION-IMPLEMENTATION-CONTRACT.md`.
- Added host harness: `test/fast-path-v1/contract-harness.mjs`, `contract.test.mjs`, `KOTLIN-HARNESS.md`, and `kotlin/FastPathV1ContractTest.kt`.
- Updated `test/fixtures/fast-path-v1/README.md` and `fixture.schema.json`; added `canonical-route-vectors.json`.
- Shared fixture case files: `app-version-signature-drift.json`, `cancellation-after-dispatch-possible.json`, `cancellation-before-dispatch.json`, `cancellation-during-execution.json`, `concurrent-execution.json`, `crash-after-dispatch-possible.json`, `duplicate-json-keys.json`, `duplicate-request.json`, `expired-lease.json`, `forged-promotion-claim.json`, `host-companion-journal-disagreement.json`, `incomplete-snapshot.json`, `journal-before-action-failure.json`, `missing-protocol.json`, `multiple-matching-targets.json`, `protocol-downgrade.json`, `revoked-route.json`, `route-fingerprint-mismatch.json`, `stale-snapshot.json`, `stale-status-result.json`, `unknown-protocol.json`, `unverified-destination.json`, `valid-evidence-capture.json`, `valid-execute-result.json`, `valid-promotion.json`, `valid-request-response.json`, `valid-revocation.json`, `valid-route-status.json`, `valid-route-verify.json`, `wrong-device.json`, `wrong-owner.json`, `wrong-package.json`, `wrong-session.json`, `wrong-signing-authority.json`, `wrong-task.json`, and `wrong-window.json`.
- The checked-in M0 failure fixtures no longer carry an unrelated inherited success response; this cleanup is documented above and does not change their expected refusal behavior.

Updated documents, protocol schema, 36 shared contract cases, canonical vectors, host test harness and Kotlin adapter skeleton are included in `artifacts/Tessera-Fast-Path-v1-M1.zip`. Archive excludes production databases, APKs, device data, secrets, signing keys, `node_modules` and Git metadata. SHA-256 and manifest verification are reported in the final response after packaging.
