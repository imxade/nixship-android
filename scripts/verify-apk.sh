#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
apk_path="${1:-}"

if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
  echo "Usage: scripts/verify-apk.sh /absolute/path/to/nixship-android.apk" >&2
  exit 2
fi
apk_path="$(realpath "$apk_path")"

for command in jq readelf sha256sum unzip wc; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run through nix develop." >&2
    exit 2
  fi
done

build_tools_version="$(jq -r '.android.buildToolsVersion' "$configuration_file")"
build_tools_root="${ANDROID_SDK_ROOT:?Run through nix develop}/build-tools/$build_tools_version"
aapt2="$build_tools_root/aapt2"
zipalign="$build_tools_root/zipalign"
if [[ ! -x "$aapt2" || ! -x "$zipalign" ]]; then
  echo "Configured Android Build Tools are unavailable under $build_tools_root." >&2
  exit 2
fi

badging="$("$aapt2" dump badging "$apk_path")"
application_id="$(jq -r '.android.applicationId' "$configuration_file")"
version_name="$(jq -r '.android.versionName' "$configuration_file")"
minimum_sdk="$(jq -r '.android.minSdk' "$configuration_file")"
target_sdk="$(jq -r '.android.targetSdk' "$configuration_file")"
configured_abi="${EXPECTED_ANDROID_ABI:-$(jq -r '.android.abi' "$configuration_file")}"
runtime_system="$(jq -r --arg abi "$configured_abi" '.runtime.nixSystemByAndroidAbi[$abi] // empty' \
  "$configuration_file")"
if [[ -z "$runtime_system" ]]; then
  echo "No configured Nix runtime system exists for APK ABI $configured_abi." >&2
  exit 2
fi

grep -F "package: name='$application_id'" <<<"$badging" >/dev/null
grep -F "versionName='$version_name'" <<<"$badging" >/dev/null
grep -F "minSdkVersion:'$minimum_sdk'" <<<"$badging" >/dev/null
grep -F "targetSdkVersion:'$target_sdk'" <<<"$badging" >/dev/null
grep -F "native-code: '$configured_abi'" <<<"$badging" >/dev/null

unexpected_native_library="$(
  unzip -Z1 "$apk_path" |
    awk -v expected="lib/$configured_abi/" '/^lib\// && index($0, expected) != 1 { print; exit }'
)"
if [[ -n "$unexpected_native_library" ]]; then
  echo "APK contains an unexpected ABI: $unexpected_native_library" >&2
  exit 1
fi

bootstrap_asset="assets/$(jq -r '.bootstrap.assetDirectory' "$configuration_file")/$(jq -r '.bootstrap.assetName' "$configuration_file")"
if ! unzip -Z1 "$apk_path" | grep -Fx "$bootstrap_asset" >/dev/null; then
  echo "APK is missing bundled Nix-on-Droid bootstrap asset." >&2
  exit 1
fi
expected_bootstrap_sha256="$(jq -r --arg abi "$configured_abi" '.bootstrap.embeddedSha256ByAndroidAbi[$abi] // empty' \
  "$configuration_file")"
expected_bootstrap_archive="$(jq -r --arg abi "$configured_abi" '.bootstrap.archiveNameByAndroidAbi[$abi] // empty' \
  "$configuration_file")"
if [[ -z "$expected_bootstrap_sha256" || -z "$expected_bootstrap_archive" ]]; then
  echo "No pinned bootstrap metadata exists for APK ABI $configured_abi." >&2
  exit 2
fi
actual_bootstrap_sha256="$(unzip -p "$apk_path" "$bootstrap_asset" | sha256sum | cut -d ' ' -f 1)"
actual_bootstrap_bytes="$(unzip -p "$apk_path" "$bootstrap_asset" | wc -c | tr -d ' ')"
bootstrap_max_bytes="$(jq -r '.bootstrap.archiveMaxBytes' "$configuration_file")"
if [[ "$actual_bootstrap_sha256" != "$expected_bootstrap_sha256" ]]; then
  echo "Bundled bootstrap SHA-256 does not match the pinned value for $expected_bootstrap_archive." >&2
  exit 1
fi
if (( actual_bootstrap_bytes <= 0 || actual_bootstrap_bytes > bootstrap_max_bytes )); then
  echo "Bundled bootstrap size is outside its configured bounds." >&2
  exit 1
fi

verification_root="$(mktemp -d)"
trap 'rm -r -- "$verification_root"' EXIT
bootstrap_path="$verification_root/bootstrap.zip"
proot_path="$verification_root/proot-static"
unzip -p "$apk_path" "$bootstrap_asset" >"$bootstrap_path"
if ! unzip -Z1 "$bootstrap_path" | grep -Fx 'bin/proot-static' >/dev/null; then
  echo "Bundled bootstrap is missing bin/proot-static." >&2
  exit 1
fi
unzip -p "$bootstrap_path" 'bin/proot-static' >"$proot_path"
expected_tls_alignment="$(jq -r '.proot.tlsAlignmentBytes' "$configuration_file")"
expected_page_alignment="$(jq -r '.proot.elfPageAlignmentBytes' "$configuration_file")"
actual_tls_alignment="$(readelf -lW "$proot_path" | awk '$1 == "TLS" { print $NF }')"
if [[ ! "$actual_tls_alignment" =~ ^0x[0-9a-fA-F]+$ ]] \
  || (( actual_tls_alignment < expected_tls_alignment )); then
  echo "Bundled proot PT_TLS alignment is invalid: ${actual_tls_alignment:-missing}." >&2
  exit 1
fi
load_segments=0
while read -r load_alignment; do
  load_segments=$((load_segments + 1))
  if [[ ! "$load_alignment" =~ ^0x[0-9a-fA-F]+$ ]] \
    || (( load_alignment < expected_page_alignment )); then
    echo "Bundled proot PT_LOAD alignment is invalid: $load_alignment." >&2
    exit 1
  fi
done < <(readelf -lW "$proot_path" | awk '$1 == "LOAD" { print $NF }')
if (( load_segments == 0 )); then
  echo "Bundled proot has no PT_LOAD segments." >&2
  exit 1
fi

asset_directory="$(jq -r '.controlPlane.assetDirectory' "$configuration_file")"
for required_asset in runtime.json runtime.nar.bundle; do
  if ! unzip -Z1 "$apk_path" | grep -Fx "assets/$asset_directory/$required_asset" >/dev/null; then
    echo "APK is missing bundled control-plane asset: $required_asset" >&2
    exit 1
  fi
done

provenance="$(unzip -p "$apk_path" "assets/$asset_directory/runtime.json")"
jq -e \
  --arg repository "$(jq -r '.controlPlane.repositoryUrl' "$configuration_file")" \
  --arg branch "$(jq -r '.controlPlane.branch' "$configuration_file")" \
  --arg revision "$(jq -r '.controlPlane.revision' "$configuration_file")" \
  --arg system "$runtime_system" \
  --argjson archiveMaxBytes "$(jq -r '.runtime.archiveMaxBytes' "$configuration_file")" \
  '
    .schemaVersion == 1 and
    .repository == $repository and
    .branch == $branch and
    .revision == $revision and
    .system == $system and
    .archive == "runtime.nar.bundle" and
    (.storePath | test("^/nix/store/[0-9a-df-np-sv-z]{32}-[A-Za-z0-9+._?=-]+$")) and
    (.archiveSha256 | test("^[0-9a-f]{64}$")) and
    (.archiveBytes > 0 and .archiveBytes <= $archiveMaxBytes)
  ' \
  <<<"$provenance" >/dev/null

runtime_asset="assets/$asset_directory/$(jq -r '.archive' <<<"$provenance")"
expected_archive_sha256="$(jq -r '.archiveSha256' <<<"$provenance")"
expected_archive_bytes="$(jq -r '.archiveBytes' <<<"$provenance")"
actual_archive_sha256="$(unzip -p "$apk_path" "$runtime_asset" | sha256sum | cut -d ' ' -f 1)"
actual_archive_bytes="$(unzip -p "$apk_path" "$runtime_asset" | wc -c | tr -d ' ')"
if [[ "$actual_archive_sha256" != "$expected_archive_sha256" ]]; then
  echo "Bundled runtime SHA-256 does not match runtime.json." >&2
  exit 1
fi
if [[ "$actual_archive_bytes" != "$expected_archive_bytes" ]]; then
  echo "Bundled runtime size does not match runtime.json." >&2
  exit 1
fi

"$zipalign" -c -P 16 4 "$apk_path" >/dev/null
printf 'Verified %s (%s, SDK %s-%s, bootstrap %s bytes, proot TLS %s/page %s, Nix Ship %s, runtime %s bytes)\n' \
  "$application_id" "$configured_abi" "$minimum_sdk" "$target_sdk" \
  "$actual_bootstrap_bytes" "$actual_tls_alignment" "$expected_page_alignment" \
  "$(jq -r '.controlPlane.revision' "$configuration_file")" \
  "$actual_archive_bytes"
