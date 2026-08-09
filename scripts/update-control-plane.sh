#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
skip_flake_check=false

if (( $# > 1 )); then
  echo "Usage: scripts/update-control-plane.sh [--skip-flake-check]" >&2
  exit 2
fi
if (( $# == 1 )); then
  if [[ "$1" != "--skip-flake-check" ]]; then
    echo "Usage: scripts/update-control-plane.sh [--skip-flake-check]" >&2
    exit 2
  fi
  skip_flake_check=true
fi

for command in git jq nix-prefetch-git; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run through nix develop." >&2
    exit 2
  fi
done

repository_url="$(jq -r '.controlPlane.repositoryUrl' "$configuration_file")"
branch="$(jq -r '.controlPlane.branch' "$configuration_file")"
revision="$(
  git ls-remote --exit-code "$repository_url" "refs/heads/$branch" |
    awk 'NR == 1 { print $1 }'
)"
if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Unable to resolve a 40-character revision for $repository_url#$branch." >&2
  exit 1
fi

prefetch_result="$(nix-prefetch-git --quiet --url "$repository_url" --rev "$revision")"
source_hash="$(jq -r '.hash // .sha256 // empty' <<<"$prefetch_result")"
if [[ -z "$source_hash" ]]; then
  echo "nix-prefetch-git did not return a source hash." >&2
  exit 1
fi

temporary_configuration="$(mktemp "$repository_root/config/product.json.XXXXXX")"
cleanup_temporary_configuration() {
  rm -f "$temporary_configuration"
}
trap cleanup_temporary_configuration EXIT
jq \
  --arg revision "$revision" \
  --arg sourceHash "$source_hash" \
  '.controlPlane.revision = $revision | .controlPlane.sourceHash = $sourceHash' \
  "$configuration_file" >"$temporary_configuration"
mv "$temporary_configuration" "$configuration_file"
trap - EXIT

if [[ "$skip_flake_check" == "true" ]]; then
  echo "Skipping configuration check (--skip-flake-check)."
else
  nix build "$repository_root#checks.x86_64-linux.configuration" --print-build-logs
fi
printf 'Updated %s#%s to %s\n' "$repository_url" "$branch" "$revision"
