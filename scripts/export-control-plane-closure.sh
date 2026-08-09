#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
output_directory="${1:-}"

if [[ -z "$output_directory" ]]; then
  echo "Usage: scripts/export-control-plane-closure.sh OUTPUT_DIRECTORY" >&2
  exit 2
fi

for command in gzip jq nix nix-store sha256sum uname; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run through nix develop." >&2
    exit 2
  fi
done

production_abi="$(jq -r '.android.abi' "$configuration_file")"
runtime_system="$(jq -r --arg abi "$production_abi" \
  '.runtime.nixSystemByAndroidAbi[$abi] // empty' "$configuration_file")"
host_architecture="$(uname -m)"
if [[ "$runtime_system" != "$host_architecture-linux" ]]; then
  echo "Closure export requires a native $runtime_system runner; host is $host_architecture-linux." >&2
  exit 2
fi

mkdir -p "$output_directory"
output_directory="$(realpath "$output_directory")"
archive="$output_directory/control-plane-closure.nar.gz"
checksum="$output_directory/control-plane-closure.nar.gz.sha256"
metadata="$output_directory/control-plane-closure.json"

package_path="$(nix build \
  ".#packages.$runtime_system.control-plane" \
  --no-link \
  --print-out-paths \
  --print-build-logs)"
mapfile -t closure_paths < <(nix-store --query --requisites "$package_path")
if (( ${#closure_paths[@]} == 0 )); then
  echo "Nix Ship runtime closure is empty." >&2
  exit 1
fi

nix-store --export "${closure_paths[@]}" | gzip -n -1 >"$archive"
archive_sha256="$(sha256sum "$archive" | cut -d ' ' -f 1)"
printf '%s  %s\n' "$archive_sha256" "$(basename "$archive")" >"$checksum"
jq -n \
  --arg system "$runtime_system" \
  --arg storePath "$package_path" \
  --arg revision "$(jq -r '.controlPlane.revision' "$configuration_file")" \
  --arg archiveSha256 "$archive_sha256" \
  --argjson archiveBytes "$(stat -c %s "$archive")" \
  --argjson closurePaths "${#closure_paths[@]}" \
  '{
    system: $system,
    storePath: $storePath,
    revision: $revision,
    archiveSha256: $archiveSha256,
    archiveBytes: $archiveBytes,
    closurePaths: $closurePaths
  }' >"$metadata"

printf 'Exported %s paths for %s to %s\n' \
  "${#closure_paths[@]}" "$runtime_system" "$archive"
