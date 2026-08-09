#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
# shellcheck source=scripts/maestro-common.sh
source "$repository_root/scripts/maestro-common.sh"
apk_path="${1:-${ANDROID_APK:-}}"

if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
  echo "Usage: ANDROID_APK=/absolute/path/to/nixship-android.apk nix develop --command scripts/run-maestro-physical.sh" >&2
  exit 2
fi
apk_path="$(realpath "$apk_path")"

for command in adb jq maestro openssl sha256sum unzip; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run this script through nix develop." >&2
    exit 2
  fi
done

mapfile -t connected_devices < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
physical_devices=()
for candidate in "${connected_devices[@]}"; do
  if [[ "$(adb -s "$candidate" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
    physical_devices+=("$candidate")
  fi
done

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  device_serial="$ANDROID_SERIAL"
  if [[ " ${physical_devices[*]} " != *" $device_serial "* ]]; then
    echo "ANDROID_SERIAL does not identify a connected physical Android device: $device_serial" >&2
    exit 2
  fi
elif [[ "${#physical_devices[@]}" -eq 1 ]]; then
  device_serial="${physical_devices[0]}"
else
  echo "Exactly one physical Android device is required; found ${#physical_devices[@]}." >&2
  echo "Set ANDROID_SERIAL when more than one physical device is attached." >&2
  exit 2
fi

device_abi="$(adb -s "$device_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
device_sdk="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
minimum_sdk="$(jq -r '.android.minSdk' "$configuration_file")"
expected_abi="$(jq -r '.android.abi' "$configuration_file")"
if [[ "$device_abi" != "$expected_abi" ]]; then
  echo "Physical acceptance requires $expected_abi; device reports $device_abi." >&2
  exit 2
fi
if (( device_sdk < minimum_sdk )); then
  echo "Device SDK $device_sdk is below the configured minimum SDK $minimum_sdk." >&2
  exit 2
fi

application_id="$(jq -r '.android.applicationId' "$configuration_file")"
asset_directory="$(jq -r '.controlPlane.assetDirectory' "$configuration_file")"
build_tools_version="$(jq -r '.android.buildToolsVersion' "$configuration_file")"
aapt2="$ANDROID_SDK_ROOT/build-tools/$build_tools_version/aapt2"
if [[ ! -x "$aapt2" ]]; then
  echo "Configured aapt2 is unavailable: $aapt2" >&2
  exit 2
fi
if ! "$aapt2" dump badging "$apk_path" | grep -F "package: name='$application_id'" >/dev/null; then
  echo "APK package does not match configured applicationId $application_id." >&2
  exit 2
fi
if ! unzip -Z1 "$apk_path" | grep -F "assets/$asset_directory/runtime.json" >/dev/null; then
  echo "APK does not contain the configured bundled Nix Ship runtime." >&2
  exit 2
fi

run_id="$(date -u +%Y%m%dT%H%M%SZ)"
artifacts_root="${MAESTRO_ARTIFACTS:-$repository_root/artifacts/maestro/$run_id}"
debug_root="$(mktemp -d)"
mkdir -p "$artifacts_root/app-logs"

maestro_create_test_credentials

cleanup_and_capture() {
  test_status=$?
  set +e
  adb -s "$device_serial" logcat -d >"$artifacts_root/logcat.txt"
  adb -s "$device_serial" shell dumpsys activity services "$application_id" \
    >"$artifacts_root/services.txt"
  adb -s "$device_serial" shell dumpsys package "$application_id" \
    >"$artifacts_root/package.txt"
  maestro_capture_app_logs "$device_serial" "$application_id" "$artifacts_root"
  maestro_publish_redacted_debug "$artifacts_root" "$debug_root"
  if ((test_status != 0)); then
    maestro_print_failure_summary "$artifacts_root" "$configuration_file"
  fi
  adb -s "$device_serial" shell svc power stayon false >/dev/null
  exit "$test_status"
}
trap cleanup_and_capture EXIT

device_fingerprint="$(adb -s "$device_serial" shell getprop ro.build.fingerprint | tr -d '\r')"
device_manufacturer="$(adb -s "$device_serial" shell getprop ro.product.manufacturer | tr -d '\r')"
device_model="$(adb -s "$device_serial" shell getprop ro.product.model | tr -d '\r')"
apk_sha256="$(sha256sum "$apk_path" | awk '{ print $1 }')"
jq -n \
  --arg serial "$device_serial" \
  --arg abi "$device_abi" \
  --arg sdk "$device_sdk" \
  --arg fingerprint "$device_fingerprint" \
  --arg manufacturer "$device_manufacturer" \
  --arg model "$device_model" \
  --arg apk "$apk_path" \
  --arg apkSha256 "$apk_sha256" \
  --arg controlPlaneRevision "$(jq -r '.controlPlane.revision' "$configuration_file")" \
  '{
    device: {
      serial: $serial,
      abi: $abi,
      sdk: ($sdk | tonumber),
      fingerprint: $fingerprint,
      manufacturer: $manufacturer,
      model: $model
    },
    apk: {path: $apk, sha256: $apkSha256},
    controlPlaneRevision: $controlPlaneRevision
  }' >"$artifacts_root/evidence.json"

adb -s "$device_serial" install -r "$apk_path"
adb -s "$device_serial" shell pm clear "$application_id"
if (( device_sdk >= 33 )); then
  adb -s "$device_serial" shell pm grant "$application_id" android.permission.POST_NOTIFICATIONS
fi
adb -s "$device_serial" shell svc power stayon true
adb -s "$device_serial" logcat -c

maestro_run_full_journey \
  "$device_serial" "$application_id" "$configuration_file" "$artifacts_root" "$debug_root" \
  "$repository_root/maestro/flows/full-journey.yaml"
