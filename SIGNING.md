# Persistent APK signing — Codex Companion

**Status:** Persistent GitHub Actions signing secrets are configured. APK workflows fail closed unless the key's public certificate matches the installed Companion's pinned signer. No private key is stored in this repository.

## Why it changed
Some candidate workflows omitted the persistent keystore environment, so Android generated an unrelated runner debug key. Those artifacts cannot update the installed application. The current installed Companion (package `com.codexedge.glow`, versionCode 18) has the persistent certificate fingerprint below.

## Private key and GitHub setup
A unique PKCS#12 signing key was generated outside this public repository and delivered to the user as a private signing kit. Retain an offline backup. It must **never** be committed.

Alias: `codexcompanion`

Expected signing certificate SHA-256:
`37:D7:92:3B:C1:49:50:6E:0D:5F:BE:D5:29:8F:B4:45:6B:F1:76:F7:96:34:17:0F:1B:74:A5:24:1F:3B:68:EB`

Required repository secrets (Settings → Secrets and variables → Actions):
- `CODEX_SIGNING_KEYSTORE_BASE64`: base64 of the `.p12` key file (no newlines)
- `CODEX_SIGNING_STORE_PASSWORD`: PKCS12 store password
- `CODEX_SIGNING_KEY_ALIAS`: `codexcompanion`
- `CODEX_SIGNING_KEY_PASSWORD`: matching private key password

The private kit contains a `setup-github-secrets.sh` script to upload secrets securely using `gh secret set` after `gh auth login` on the user's own phone.

## Build
The workflows validate all four secret names are populated, decode the existing keystore only into a mode-600 runner temporary file, check that its public certificate fingerprint matches the installed Companion, and then sign both APKs with that same identity. The certificate is public; the keystore and passwords are never logged or uploaded.

Build both candidates through either workflow:
```bash
gh workflow run m9-fastpath-runtime.yml --repo kunokun12/Codex-Edge-Glow --ref feature/fastpath-v1-m9-1-android-wiring
```
The M9 workflow runs the Android unit tests, builds Companion and Observer, verifies APK v2 signatures and package IDs, checks both signer fingerprints, checks that the two manifests do not declare a shared UID, and confirms the Observer IPC permission remains signature protected.

## Migration
The installed Companion's signer has been independently read from the installed APK and matches this persistent certificate. Candidate versionCode must remain at least 18. Therefore a candidate signed by the verified persistent key is Android signature/version compatible with an in-place update; device installation is a separate action and is not performed by CI.

Keep the signing key private and backed up. This is a development-only, debuggable application that still requires the Android MCP's `run-as` bridge; never distribute a debug build as a production security model.
