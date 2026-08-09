#!/usr/bin/env bash
# Resolve the latest control-plane source and patch product.json with the given
# version metadata. This script is designed for CI; it modifies the working tree
# in-place without committing.
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"

usage() {
  cat >&2 <<'EOF'
Usage: scripts/prepare-release.sh --version-name NAME --version-code CODE

Resolves the latest control-plane HEAD, patches config/product.json with the
resolved revision, source hash, and the supplied version metadata, then
validates the result with nix flake check.

Options:
  --version-name NAME   Android versionName  (e.g. 0.1.0-rc.15)
  --version-code CODE   Android versionCode  (positive integer)
  --skip-flake-check    Skip nix flake check  (useful when Nix is unavailable)
EOF
  exit 2
}

version_name=""
version_code=""
skip_flake_check=false

while (( $# > 0 )); do
  case "$1" in
    --version-name)  version_name="${2:-}"; shift 2 ;;
    --version-code)  version_code="${2:-}"; shift 2 ;;
    --skip-flake-check)  skip_flake_check=true; shift ;;
    *)  usage ;;
  esac
done

if [[ -z "$version_name" || -z "$version_code" ]]; then
  echo "Both --version-name and --version-code are required." >&2
  usage
fi
if ! [[ "$version_code" =~ ^[1-9][0-9]*$ ]]; then
  echo "version-code must be a positive integer; got '$version_code'." >&2
  exit 1
fi
# Semver (with optional pre-release) validation matching the flake check.
if ! [[ "$version_name" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "version-name must be valid semver; got '$version_name'." >&2
  exit 1
fi

for command in git jq nix-prefetch-git; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run through nix develop." >&2
    exit 2
  fi
done

# --- Resolve the latest control-plane source ---

repository_url="$(jq -r '.controlPlane.repositoryUrl' "$configuration_file")"
branch="$(jq -r '.controlPlane.branch' "$configuration_file")"
echo "Resolving latest HEAD of $repository_url#$branch ..."
revision="$(
  git ls-remote --exit-code "$repository_url" "refs/heads/$branch" |
    awk 'NR == 1 { print $1 }'
)"
if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Unable to resolve a 40-character revision for $repository_url#$branch." >&2
  exit 1
fi
echo "Resolved control-plane revision: $revision"

prefetch_result="$(nix-prefetch-git --quiet --url "$repository_url" --rev "$revision")"
source_hash="$(jq -r '.hash // .sha256 // empty' <<<"$prefetch_result")"
if [[ -z "$source_hash" ]]; then
  echo "nix-prefetch-git did not return a source hash." >&2
  exit 1
fi
echo "Computed source hash: $source_hash"

# --- Patch config/product.json ---

temporary_configuration="$(mktemp "$repository_root/config/product.json.XXXXXX")"
cleanup_temporary_configuration() {
  rm -f "$temporary_configuration"
}
trap cleanup_temporary_configuration EXIT
jq \
  --arg revision "$revision" \
  --arg sourceHash "$source_hash" \
  --argjson versionCode "$version_code" \
  --arg versionName "$version_name" \
  '
    .controlPlane.revision = $revision |
    .controlPlane.sourceHash = $sourceHash |
    .android.versionCode = $versionCode |
    .android.versionName = $versionName
  ' \
  "$configuration_file" >"$temporary_configuration"
mv "$temporary_configuration" "$configuration_file"
trap - EXIT

echo "Patched $configuration_file:"
jq '{version: .android.versionName, code: .android.versionCode, revision: .controlPlane.revision}' \
  "$configuration_file"

# --- Write build-info.json for provenance ---

build_info="$repository_root/config/build-info.json"
jq -n \
  --arg versionName "$version_name" \
  --argjson versionCode "$version_code" \
  --arg controlPlaneRevision "$revision" \
  --arg controlPlaneSourceHash "$source_hash" \
  --arg repositoryUrl "$repository_url" \
  --arg branch "$branch" \
  --arg buildTimestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg androidCommit "$(git -C "$repository_root" rev-parse HEAD 2>/dev/null || echo unknown)" \
  '{
    versionName: $versionName,
    versionCode: $versionCode,
    controlPlane: {
      repositoryUrl: $repositoryUrl,
      branch: $branch,
      revision: $controlPlaneRevision,
      sourceHash: $controlPlaneSourceHash
    },
    android: {
      commit: $androidCommit
    },
    buildTimestamp: $buildTimestamp
  }' >"$build_info"
echo "Wrote build info to $build_info"

# --- Validate ---

if [[ "$skip_flake_check" == "true" ]]; then
  echo "Skipping nix flake check (--skip-flake-check)."
else
  echo "Running nix flake check ..."
  nix build "$repository_root#checks.x86_64-linux.configuration" --print-build-logs
  echo "Flake configuration check passed."
fi

printf '\nRelease preparation complete: %s (code %s) at control-plane %s\n' \
  "$version_name" "$version_code" "$revision"
