# Reproducible verification and build instructions

## Companion source build

Prerequisites are JDK 17, Gradle 8.9, Android SDK platform 35, Android build-tools 35.0.0, and the Android SDK licenses accepted. From the Companion repository root:

```sh
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleDebug
```

The expected output is `app/build/outputs/apk/debug/app-debug.apk`. To reproduce the designated hosted build, use the `Build Codex Companion (Persistent Signing)` GitHub Actions workflow on the exact source commit; it downloads the Android SDK, validates repository signing secrets without printing them, runs JVM tests, builds the debug APK, verifies APK v2 signing, and writes `build-provenance/BUILD-INFO.txt`. Never put the signing keystore or private keys into the repository or artifact bundle.

The host project is `/root/android-mcp`; it uses Node.js 24 and the existing dependencies. Run `npm test`. The explicit test globs scope discovery to maintained `test/*.test.mjs`, `test/*.test.cjs`, and `test/fast-path-v1/*.test.mjs`, excluding previously packaged artifact snapshots and backup files that Node's default recursive discovery would otherwise execute as duplicate tests.

## Candidate vector

The experimental fingerprint vector is stored in both:

- Companion: `app/src/test/resources/fastpath-v1/ui-state-candidate-v2-vectors.json`
- Host: `/root/android-mcp/test/fixtures/fast-path-v1-vectors/ui-state-candidate-v2-vectors.json`

The host test and Kotlin unit test compare their outputs with the same expected lowercase SHA-256. This is test-vector agreement for an unapproved proposal, not M1 conformance approval.

## Local PRoot limitation

In the current Debian ARM64 Termux PRoot, Gradle 8.9 resolves the Android SDK at `/usr/lib/android-sdk` but AAPT2 daemon startup fails during `:app:processDebugResources`. The exact-commit GitHub Actions run is the authoritative Android JVM/build evidence. Do not treat that hosted result as a local or device build.
