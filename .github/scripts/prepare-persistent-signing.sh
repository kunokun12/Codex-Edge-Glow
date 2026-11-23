#!/usr/bin/env bash
set -euo pipefail

# Public certificate pin only. Never print or persist any private signing input.
readonly EXPECTED_CERT_SHA256="37d7923bc149506e0d5fbed5298fb4456bf176f79634170f1b74a5241f3b68eb"
for key in CODEX_SIGNING_KEYSTORE_BASE64 CODEX_SIGNING_STORE_PASSWORD CODEX_SIGNING_KEY_ALIAS CODEX_SIGNING_KEY_PASSWORD; do
  if [[ -z "${!key:-}" ]]; then
    echo "::error::Missing required GitHub Actions signing secret: ${key}"
    exit 1
  fi
done

umask 077
readonly KEYSTORE_PATH="$RUNNER_TEMP/codex-companion-signing.p12"
readonly CERT_PATH="$RUNNER_TEMP/codex-companion-signing.der"
printf '%s' "$CODEX_SIGNING_KEYSTORE_BASE64" | base64 --decode > "$KEYSTORE_PATH"
keytool -list -keystore "$KEYSTORE_PATH" -storepass "$CODEX_SIGNING_STORE_PASSWORD" \
  -alias "$CODEX_SIGNING_KEY_ALIAS" > /dev/null
keytool -exportcert -keystore "$KEYSTORE_PATH" -storepass "$CODEX_SIGNING_STORE_PASSWORD" \
  -alias "$CODEX_SIGNING_KEY_ALIAS" -file "$CERT_PATH" > /dev/null
actual_cert_sha256="$(sha256sum "$CERT_PATH" | cut -d' ' -f1)"
if [[ "$actual_cert_sha256" != "$EXPECTED_CERT_SHA256" ]]; then
  echo "::error::Persistent signing certificate does not match the installed Companion identity."
  exit 1
fi
echo "Persistent signing certificate pin verified: $actual_cert_sha256"
{
  echo "CODEX_SIGNING_KEYSTORE_PATH=$KEYSTORE_PATH"
  echo "CODEX_SIGNING_KEY_ALIAS=$CODEX_SIGNING_KEY_ALIAS"
} >> "$GITHUB_ENV"
