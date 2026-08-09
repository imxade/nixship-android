#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
# shellcheck source=scripts/maestro-common.sh
source "$repository_root/scripts/maestro-common.sh"
apk_path="${1:-${ANDROID_APK:-}}"

if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
  echo "Usage: ANDROID_APK=/absolute/path/to/nixship-android.apk nix develop --command scripts/run-maestro-emulator.sh" >&2
  exit 2
fi
apk_path="$(realpath "$apk_path")"

for command in adb jq maestro openssl sha256sum; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run this script through nix develop." >&2
    exit 2
  fi
done

mapfile -t connected_emulators < <(
  adb devices |
    awk 'NR > 1 && $2 == "device" { print $1 }' |
    while read -r candidate; do
      if [[ "$(adb -s "$candidate" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]]; then
        printf '%s\n' "$candidate"
      fi
    done
)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  device_serial="$ANDROID_SERIAL"
  if [[ " ${connected_emulators[*]} " != *" $device_serial "* ]]; then
    echo "ANDROID_SERIAL does not identify a connected Android emulator: $device_serial" >&2
    exit 2
  fi
elif [[ "${#connected_emulators[@]}" -eq 1 ]]; then
  device_serial="${connected_emulators[0]}"
else
  echo "Exactly one Android emulator is required; found ${#connected_emulators[@]}." >&2
  echo "Set ANDROID_SERIAL when more than one emulator is running." >&2
  exit 2
fi

expected_abi="$(jq -r '.android.emulatorAbi' "$configuration_file")"
device_abi="$(adb -s "$device_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
device_sdk="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
minimum_sdk="$(jq -r '.android.minSdk' "$configuration_file")"
if [[ "$device_abi" != "$expected_abi" ]]; then
  echo "Emulator acceptance requires $expected_abi; device reports $device_abi." >&2
  exit 2
fi
if (( device_sdk < minimum_sdk )); then
  echo "Emulator SDK $device_sdk is below the configured minimum SDK $minimum_sdk." >&2
  exit 2
fi

EXPECTED_ANDROID_ABI="$expected_abi" "$repository_root/scripts/verify-apk.sh" "$apk_path"

application_id="$(jq -r '.android.applicationId' "$configuration_file")"
run_id="$(date -u +%Y%m%dT%H%M%SZ)"
artifacts_root="${MAESTRO_ARTIFACTS:-$repository_root/artifacts/maestro-emulator/$run_id}"
debug_root="$(mktemp -d)"
mkdir -p "$artifacts_root/app-logs"
status_reporter_pid=""

maestro_create_test_credentials

cleanup_and_capture() {
  test_status=$?
  set +e
  if [[ -n "$status_reporter_pid" ]] && kill -0 "$status_reporter_pid" 2>/dev/null; then
    kill "$status_reporter_pid"
    wait "$status_reporter_pid"
  fi
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

report_live_status() {
  local interval_seconds="$1"
  local runtime_event deployment_event maestro_command
  set +e
  while sleep "$interval_seconds"; do
    runtime_event="$(
      adb -s "$device_serial" exec-out run-as "$application_id" \
        sh -c 'test -f files/home/.nix-platform/logs/android-runtime.log && tail -n 1 files/home/.nix-platform/logs/android-runtime.log' \
        2>/dev/null |
        tr -d '\r'
    )"
    deployment_event="$(
      adb -s "$device_serial" exec-out run-as "$application_id" \
        sh -c 'test -f files/home/.nix-platform/logs/control-plane.log && tail -n 300 files/home/.nix-platform/logs/control-plane.log' \
        2>/dev/null |
        tr -d '\r' |
        awk '
          /"message":"(Deployment|API request failed|Application process exited)/ {
            latest = $0
          }
          END { print latest }
        ' |
        maestro_redact
    )"
    maestro_command=""
    if [[ -f "$debug_root/maestro.log" ]]; then
      maestro_command="$(
        awk '
          /TestSuiteInteractor.invoke: (Assert that|Run |Tap on|Launch app|Apply configuration|Hide keyboard|Scroll)/ {
            latest = $0
          }
          END { print latest }
        ' "$debug_root/maestro.log"
      )"
    fi
    printf '[acceptance-status] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  Runtime event: %s\n' "${runtime_event:-no runtime event yet}"
    printf '  Deployment event: %s\n' "${deployment_event:-no deployment event yet}"
    printf '  Maestro command: %s\n' "${maestro_command:-no safe command yet}"
  done
}

apk_sha256="$(sha256sum "$apk_path" | awk '{ print $1 }')"
jq -n \
  --arg serial "$device_serial" \
  --arg abi "$device_abi" \
  --arg sdk "$device_sdk" \
  --arg fingerprint "$(adb -s "$device_serial" shell getprop ro.build.fingerprint | tr -d '\r')" \
  --arg apk "$apk_path" \
  --arg apkSha256 "$apk_sha256" \
  --arg controlPlaneRevision "$(jq -r '.controlPlane.revision' "$configuration_file")" \
  '{
    device: {
      serial: $serial,
      abi: $abi,
      sdk: ($sdk | tonumber),
      fingerprint: $fingerprint,
      emulator: true
    },
    apk: {path: $apk, sha256: $apkSha256},
    controlPlaneRevision: $controlPlaneRevision,
    productionEvidence: false
  }' >"$artifacts_root/evidence.json"

wait_for_emulator() {
  for _ in $(seq 1 60); do
    if [[ "$(adb -s "$device_serial" get-state 2>/dev/null || true)" == "device" ]] \
      && [[ "$(adb -s "$device_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      return
    fi
    sleep 1
  done
  echo "Emulator did not return to a ready ADB state within 60 seconds." >&2
  exit 1
}

wait_for_emulator
adb -s "$device_serial" shell am force-stop "$application_id"
adb -s "$device_serial" install -r "$apk_path"
wait_for_emulator
adb -s "$device_serial" shell pm clear "$application_id"
adb -s "$device_serial" shell am force-stop "$application_id"
if (( device_sdk >= 33 )); then
  adb -s "$device_serial" shell pm grant "$application_id" android.permission.POST_NOTIFICATIONS
fi
adb -s "$device_serial" shell svc power stayon true
adb -s "$device_serial" logcat -c

report_live_status "$(jq -r '.acceptance.statusReportIntervalSeconds' "$configuration_file")" &
status_reporter_pid=$!
set +e
maestro_run_full_journey \
  "$device_serial" "$application_id" "$configuration_file" "$artifacts_root" "$debug_root" \
  "$repository_root/maestro/flows/full-journey.yaml"
journey_status=$?
set -e
if kill -0 "$status_reporter_pid" 2>/dev/null; then
  kill "$status_reporter_pid"
  wait "$status_reporter_pid" || true
fi
status_reporter_pid=""

application_anrs="$(
  adb -s "$device_serial" logcat -d -v brief |
    awk -v marker="ANR in $application_id" 'index($0, marker)'
)"
if [[ -n "$application_anrs" ]]; then
  echo "Nix Ship generated an Android ANR during the Maestro journey:" >&2
  printf '%s\n' "$application_anrs" >&2
  exit 1
fi
test "$journey_status" -eq 0
