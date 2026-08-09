#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
artifact_directory="${1:-}"

if [[ -z "$artifact_directory" || ! -d "$artifact_directory" ]]; then
  echo "Usage: scripts/import-control-plane-closure.sh ARTIFACT_DIRECTORY" >&2
  exit 2
fi

for command in gzip jq nix nix-store sha256sum; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run through nix develop." >&2
    exit 2
  fi
done

artifact_directory="$(realpath "$artifact_directory")"
archive="$artifact_directory/control-plane-closure.nar.gz"
checksum="$artifact_directory/control-plane-closure.nar.gz.sha256"
metadata="$artifact_directory/control-plane-closure.json"
for required_file in "$archive" "$checksum" "$metadata"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing closure artifact: $required_file" >&2
    exit 1
  fi
done

(
  cd "$artifact_directory"
  sha256sum --check "$(basename "$checksum")"
)
gzip -t "$archive"

production_abi="$(jq -r '.android.abi' "$configuration_file")"
runtime_system="$(jq -r --arg abi "$production_abi" \
  '.runtime.nixSystemByAndroidAbi[$abi] // empty' "$configuration_file")"
revision="$(jq -r '.controlPlane.revision' "$configuration_file")"
jq -e \
  --arg system "$runtime_system" \
  --arg revision "$revision" \
  --arg archiveSha256 "$(sha256sum "$archive" | cut -d ' ' -f 1)" \
  '
    .system == $system and
    .revision == $revision and
    .archiveSha256 == $archiveSha256 and
    (.storePath | startswith("/nix/store/"))
  ' "$metadata" >/dev/null

gzip -dc "$archive" | nix-store --import >/dev/null
expected_store_path="$(nix eval \
  --raw \
  ".#packages.$runtime_system.control-plane.outPath")"
declared_store_path="$(jq -r '.storePath' "$metadata")"
if [[ "$expected_store_path" != "$declared_store_path" ]]; then
  echo "Imported closure root does not match the evaluated control-plane output." >&2
  exit 1
fi
if [[ ! -x "$expected_store_path/bin/nixship" ]]; then
  echo "Imported control-plane executable is missing: $expected_store_path/bin/nixship" >&2
  exit 1
fi
nix-store --verify-path "$expected_store_path"
printf 'Imported verified %s closure at %s\n' "$runtime_system" "$expected_store_path"
