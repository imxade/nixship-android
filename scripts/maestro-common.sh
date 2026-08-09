#!/usr/bin/env bash

# Shared helpers for the physical and emulator acceptance entry points. Callers
# enable strict mode and define repository_root before invoking these functions.

maestro_create_test_credentials() {
  owner_username="${MAESTRO_USERNAME:-owner$(date -u +%s)}"
  owner_password="${MAESTRO_PASSWORD:-$(openssl rand -hex 18)}"
  owner_new_password="${MAESTRO_NEW_PASSWORD:-$(openssl rand -hex 18)}"
  if [[ "$owner_password" == "$owner_new_password" ]]; then
    echo "Old and new test passwords must differ." >&2
    return 2
  fi
  for test_password in "$owner_password" "$owner_new_password"; do
    if [[ ! "$test_password" =~ ^[[:alnum:]]{12,100}$ ]]; then
      echo "Test passwords must contain 12-100 ASCII letters or digits for safe Maestro evidence redaction." >&2
      return 2
    fi
  done
}

maestro_redact() {
  local in_place=()
  if [[ "${1:-}" == "--in-place" ]]; then
    in_place=(-i)
    shift
  fi
  sed "${in_place[@]}" \
    -e "s/$owner_password/<redacted-old-password>/g" \
    -e "s/$owner_new_password/<redacted-new-password>/g" \
    -E \
    -e 's#([?&]token=)[A-Za-z0-9._~-]+#\1<redacted-setup-token>#g' \
    -e 's#("token"[[:space:]]*:[[:space:]]*")[^"]+#\1<redacted-token>#g' \
    -e 's#(PLATFORM_MASTER_KEY[[:space:]"=:]+)[A-Za-z0-9_+/=-]+#\1<redacted-master-key>#g' \
    "$@"
}

maestro_capture_app_logs() {
  local device_serial="$1"
  local application_id="$2"
  local artifacts_root="$3"
  for log_name in android-runtime.log control-plane.log; do
    adb -s "$device_serial" exec-out run-as "$application_id" \
      sh -c "test -f files/home/.nix-platform/logs/$log_name && cat files/home/.nix-platform/logs/$log_name" \
      >"$artifacts_root/app-logs/$log_name"
  done
  adb -s "$device_serial" exec-out run-as "$application_id" \
    sh -c 'find files/home/.nix-platform/data/logs -maxdepth 4 -type f -print -exec tail -n 200 {} \;' \
    >"$artifacts_root/app-logs/workloads.log"
  adb -s "$device_serial" exec-out run-as "$application_id" \
    sh -c 'find files/home/.nix-platform/logs files/home/.nix-platform/data/logs -maxdepth 4 -type f -exec ls -ln {} \;' \
    >"$artifacts_root/app-logs/files.txt"
}

maestro_publish_redacted_debug() {
  local artifacts_root="$1"
  local debug_root="$2"
  for evidence_directory in "$artifacts_root" "$debug_root"; do
    while IFS= read -r -d "" artifact; do
      if grep -Iq . "$artifact"; then
        maestro_redact --in-place "$artifact"
      fi
    done < <(find "$evidence_directory" -type f -print0)
  done
  mkdir -p "$artifacts_root/debug"
  cp -R "$debug_root/." "$artifacts_root/debug/"
  rm -r -- "$debug_root"
}

maestro_print_failure_summary() {
  local artifacts_root="$1"
  local configuration_file="$2"
  local evidence_file
  local tail_bytes
  tail_bytes="$(jq -r '.diagnostics.displayTailBytes' "$configuration_file")"
  printf '\nRedacted Maestro failure summary:\n'
  for evidence_file in \
    "$artifacts_root/maestro-junit.xml" \
    "$artifacts_root/app-logs/control-plane.log" \
    "$artifacts_root/app-logs/workloads.log" \
    "$artifacts_root/app-logs/android-runtime.log"; do
    if [[ -s "$evidence_file" ]]; then
      printf '\n===== %s (last %s bytes) =====\n' \
        "${evidence_file#"$artifacts_root/"}" "$tail_bytes"
      tail -c "$tail_bytes" "$evidence_file"
    fi
  done
}

maestro_run_full_journey() {
  local device_serial="$1"
  local application_id="$2"
  local configuration_file="$3"
  local artifacts_root="$4"
  local debug_root="$5"
  local flow_path="$6"

  maestro --device "$device_serial" test \
    --format JUNIT \
    --output "$artifacts_root/maestro-junit.xml" \
    --debug-output "$debug_root" \
    --flatten-debug-output \
    -e APP_ID="$application_id" \
    -e OWNER_USERNAME="$owner_username" \
    -e OWNER_PASSWORD="$owner_password" \
    -e OWNER_NEW_PASSWORD="$owner_new_password" \
    -e ACCEPTANCE_APP_NAME="$(jq -r '.acceptance.applicationName' "$configuration_file")" \
    -e ACCEPTANCE_REPOSITORY_URL="$(jq -r '.acceptance.repositoryUrl' "$configuration_file")" \
    -e ACCEPTANCE_BRANCH="$(jq -r '.acceptance.branch' "$configuration_file")" \
    -e ACCEPTANCE_FLAKE_OUTPUT="$(jq -r '.acceptance.flakeOutput' "$configuration_file")" \
    -e ACCEPTANCE_READY_TEXT="$(jq -r '.acceptance.readyText' "$configuration_file")" \
    -e STARTUP_TIMEOUT_MILLIS="$(( $(jq -r '.webView.startupTimeoutSeconds' "$configuration_file") * 1000 ))" \
    -e DEPLOYMENT_TIMEOUT_MILLIS="$(jq -r '.acceptance.deploymentTimeoutMillis' "$configuration_file")" \
    -e QUICK_TUNNEL_TIMEOUT_MILLIS="$(jq -r '.acceptance.quickTunnelTimeoutMillis' "$configuration_file")" \
    -e ACCEPTANCE_READY_TIMEOUT_MILLIS="$(jq -r '.acceptance.readyTimeoutMillis' "$configuration_file")" \
    "$flow_path"
}
