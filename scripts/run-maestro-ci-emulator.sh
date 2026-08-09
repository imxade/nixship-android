#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
apk_path="${1:-${ANDROID_APK:-}}"

if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
  echo "Usage: scripts/run-maestro-ci-emulator.sh /absolute/path/to/nixship-android.apk" >&2
  exit 2
fi
apk_path="$(realpath "$apk_path")"

for command in adb avdmanager emulator jq maestro openssl readelf sha256sum; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing emulator command: $command. Run through nix develop .#emulator." >&2
    exit 2
  fi
done
if [[ ! -c /dev/kvm ]]; then
  echo "The CI emulator requires hardware virtualization at /dev/kvm." >&2
  exit 2
fi

api="$(jq -r '.android.emulatorApi' "$configuration_file")"
abi="$(jq -r '.android.emulatorAbi' "$configuration_file")"
memory_mb="$(jq -r '.android.emulatorMemoryMb' "$configuration_file")"
data_partition_gb="$(jq -r '.android.emulatorDataPartitionGb' "$configuration_file")"
data_partition_mb="$((data_partition_gb * 1024))"
system_image="system-images;android-${api};google_apis;${abi}"
avd_name="android-ci-api-${api}"
device_serial="emulator-5554"
if adb devices | awk 'NR > 1 { print $1 }' | grep -Fxq "$device_serial"; then
  echo "$device_serial is already registered with ADB; refusing to attach the disposable test to an emulator it did not start." >&2
  exit 2
fi
temporary_root="$(mktemp -d)"
evidence_root="${MAESTRO_ARTIFACTS:-$repository_root/artifacts/maestro-ci-emulator}"
mkdir -p "$evidence_root"
export ANDROID_USER_HOME="$temporary_root/android"
export ANDROID_AVD_HOME="$temporary_root/avd"
mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"

emulator_pid=""
cleanup() {
  status=$?
  set +e
  if [[ -n "$emulator_pid" ]] && kill -0 "$emulator_pid" 2>/dev/null; then
    adb -s "$device_serial" emu kill >/dev/null 2>&1
    wait "$emulator_pid"
  fi
  rm -r -- "$temporary_root"
  exit "$status"
}
trap cleanup EXIT

{
  printf 'Preparing %s with JAVA_HOME=%s\n' "$avd_name" "${JAVA_HOME:-unset}"
  "${JAVA_HOME:?The emulator shell must provide JAVA_HOME}/bin/java" -version
  df -h "$ANDROID_AVD_HOME"
  printf "no\n" |
    avdmanager create avd \
      --force \
      --name "$avd_name" \
      --package "$system_image" \
      --device pixel_2
} 2>&1 | tee "$evidence_root/ci-emulator-preflight.log"

emulator \
  -avd "$avd_name" \
  -port 5554 \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -gpu swiftshader_indirect \
  -memory "$memory_mb" \
  -partition-size "$data_partition_mb" \
  >"$evidence_root/emulator.log" 2>&1 &
emulator_pid=$!

for _ in $(seq 1 240); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo "Android emulator exited before boot completed." >&2
    exit 1
  fi
  if [[ "$(adb -s "$device_serial" get-state 2>/dev/null || true)" == "device" ]] \
    && [[ "$(adb -s "$device_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    break
  fi
  sleep 1
done
if [[ "$(adb -s "$device_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
  echo "Android emulator did not boot within 240 seconds." >&2
  exit 1
fi

# Fresh Google API images may start optional background applications while the
# host is under its heaviest I/O load. Their ANR dialogs cover the application
# under test and intercept Maestro input. This disposable AVD does not exercise
# those applications, so disable the known noisy packages and hide system error
# dialogs. The lower-level runner still checks logcat for Nix Ship's own ANRs.
adb -s "$device_serial" shell settings put global hide_error_dialogs 1
mapfile -t background_packages < <(
  jq -r '.android.emulatorDisabledPackages[]' "$configuration_file"
)
for background_package in "${background_packages[@]}"; do
  if adb -s "$device_serial" shell pm path "$background_package" | grep -q '^package:'; then
    adb -s "$device_serial" shell pm disable-user --user 0 "$background_package"
  fi
done

available_kb="$(
  adb -s "$device_serial" shell df -k /data |
    awk 'NR == 2 { print $4 }' |
    tr -d '\r'
)"
minimum_available_kb="$(((data_partition_gb - 2) * 1024 * 1024))"
if [[ ! "$available_kb" =~ ^[0-9]+$ ]] || ((available_kb < minimum_available_kb)); then
  echo "Emulator /data has ${available_kb:-unknown} KiB free; expected at least $minimum_available_kb KiB." >&2
  exit 1
fi

ANDROID_SERIAL="$device_serial" \
ANDROID_APK="$apk_path" \
MAESTRO_ARTIFACTS="$evidence_root" \
  "$repository_root/scripts/run-maestro-emulator.sh"
