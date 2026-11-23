#!/usr/bin/env bash
set -euo pipefail
readonly EXPECTED_CERT_SHA256="37d7923bc149506e0d5fbed5298fb4456bf176f79634170f1b74a5241f3b68eb"
readonly APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
readonly AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
mkdir -p build-provenance

verify_apk() {
  local label="$1" apk="$2" package="$3" info="$4"
  test -s "$apk"
  "$APKSIGNER" verify --verbose --print-certs "$apk" > "$RUNNER_TEMP/$label-apksigner.txt"
  grep -q 'Verified using v2 scheme (APK Signature Scheme v2): true' "$RUNNER_TEMP/$label-apksigner.txt"
  local cert
  cert="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$RUNNER_TEMP/$label-apksigner.txt" | head -n1 | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
  if [[ "$cert" != "$EXPECTED_CERT_SHA256" ]]; then
    echo "::error::$label APK signer does not match the installed Companion certificate."
    exit 1
  fi
  "$AAPT" dump badging "$apk" | grep -F "package: name='$package'" > /dev/null
  if [[ "$label" == companion ]]; then
    local candidate_version
    candidate_version="$("$AAPT" dump badging "$apk" | sed -n "s/.*versionCode='\([0-9][0-9]*\)'.*/\1/p" | head -n1)"
    if [[ -z "$candidate_version" || "$candidate_version" -lt 20 ]]; then
      echo '::error::Companion APK versionCode must be at least 20.'
      exit 1
    fi
  else
    local candidate_version
    candidate_version="$("$AAPT" dump badging "$apk" | sed -n "s/.*versionCode='\([0-9][0-9]*\)'.*/\1/p" | head -n1)"
    if [[ -z "$candidate_version" || "$candidate_version" -lt 3 ]]; then
      echo '::error::Observer APK versionCode must be at least 3.'
      exit 1
    fi
    local source_commit_found=false
    while IFS= read -r dex_entry; do
      [[ "$dex_entry" =~ ^classes([0-9]+)?\.dex$ ]] || continue
      unzip -p "$apk" "$dex_entry" > "$RUNNER_TEMP/observer-classes.dex"
      if [[ "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]] && grep -aFq "$GITHUB_SHA" "$RUNNER_TEMP/observer-classes.dex"; then
        source_commit_found=true
        break
      fi
    done < <(unzip -Z1 "$apk")
    if [[ "$source_commit_found" != true ]]; then
      echo '::error::Observer APK does not embed the exact GitHub source commit.'
      exit 1
    fi
  fi
  {
    echo "artifact=$label"
    echo "package=$package"
    echo "apk_sha256=$(sha256sum "$apk" | cut -d' ' -f1)"
    echo "signer_sha256=$cert"
    echo "source_commit=$(git rev-parse HEAD)"
    echo "source_branch=${GITHUB_REF_NAME}"
  } > "build-provenance/$label.txt"
}

verify_apk companion app/build/outputs/apk/debug/app-debug.apk com.codexedge.glow companion
verify_apk observer observer/build/outputs/apk/debug/observer-debug.apk org.tessera.observer observer

# Shared signer allows signature permission checks; distinct application IDs
# and absence of sharedUserId keep the Android packages on separate UIDs.
"$AAPT" dump xmltree app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml > "$RUNNER_TEMP/companion-manifest.txt"
"$AAPT" dump xmltree observer/build/outputs/apk/debug/observer-debug.apk AndroidManifest.xml > "$RUNNER_TEMP/observer-manifest.txt"
if grep -E 'sharedUserId' "$RUNNER_TEMP/companion-manifest.txt" "$RUNNER_TEMP/observer-manifest.txt"; then
  echo '::error::A shared Android UID is forbidden.'
  exit 1
fi
grep -F 'org.tessera.observer.permission.READ_EVIDENCE' "$RUNNER_TEMP/companion-manifest.txt" "$RUNNER_TEMP/observer-manifest.txt" > /dev/null
grep -F 'org.tessera.observer.ObserverIpcService' "$RUNNER_TEMP/observer-manifest.txt" > /dev/null
grep -F 'android:protectionLevel="signature"' observer/src/main/AndroidManifest.xml > /dev/null
grep -F 'android:permission="org.tessera.observer.permission.READ_EVIDENCE"' observer/src/main/AndroidManifest.xml > /dev/null
echo 'Android UID separation and signature-protected observer IPC source checks passed.'
