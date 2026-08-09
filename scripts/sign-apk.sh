#!/usr/bin/env bash
set -euo pipefail
umask 077

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
unsigned_apk="${1:-}"
signed_apk="${2:-}"

if [[ -z "$unsigned_apk" || ! -f "$unsigned_apk" || -z "$signed_apk" ]]; then
  echo "Usage: scripts/sign-apk.sh unsigned.apk signed.apk" >&2
  exit 2
fi
unsigned_apk="$(realpath "$unsigned_apk")"
signed_parent="$(dirname "$signed_apk")"
mkdir -p "$signed_parent"
signed_apk="$(realpath "$signed_parent")/$(basename "$signed_apk")"

: "${ANDROID_RELEASE_STORE_PASSWORD:?Missing release store password}"
: "${ANDROID_RELEASE_KEY_ALIAS:?Missing release key alias}"
: "${ANDROID_RELEASE_KEY_PASSWORD:?Missing release key password}"

build_tools_version="$(jq -r '.android.buildToolsVersion' "$configuration_file")"
apksigner="${ANDROID_SDK_ROOT:?Run through nix develop}/build-tools/$build_tools_version/apksigner"
if [[ ! -x "$apksigner" ]]; then
  echo "Configured apksigner is unavailable: $apksigner" >&2
  exit 2
fi

signing_directory="$(mktemp -d)"
cleanup_signing_directory() {
  rm -rf "$signing_directory"
}
trap cleanup_signing_directory EXIT

if [[ -n "${ANDROID_RELEASE_KEYSTORE:-}" ]]; then
  keystore="$(realpath "$ANDROID_RELEASE_KEYSTORE")"
else
  : "${ANDROID_RELEASE_KEYSTORE_BASE64:?Missing base64 release keystore}"
  keystore="$signing_directory/android-release.jks"
  printf '%s' "$ANDROID_RELEASE_KEYSTORE_BASE64" | base64 --decode >"$keystore"
fi

"$apksigner" sign \
  --ks "$keystore" \
  --ks-key-alias "$ANDROID_RELEASE_KEY_ALIAS" \
  --ks-pass env:ANDROID_RELEASE_STORE_PASSWORD \
  --key-pass env:ANDROID_RELEASE_KEY_PASSWORD \
  --out "$signed_apk" \
  "$unsigned_apk"
"$apksigner" verify --verbose --print-certs "$signed_apk"
"$repository_root/scripts/verify-apk.sh" "$signed_apk"
