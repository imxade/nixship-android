#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"

for command in jq nix; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run this script through nix develop." >&2
    exit 2
  fi
done

repository_url="$(jq -r '.acceptance.repositoryUrl' "$configuration_file")"
branch="$(jq -r '.acceptance.branch' "$configuration_file")"
flake_output="$(jq -r '.acceptance.flakeOutput' "$configuration_file")"
if [[ ! "$repository_url" =~ ^https://github\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\.git$ ]]; then
  echo "Acceptance repository must be an HTTPS GitHub .git URL: $repository_url" >&2
  exit 2
fi
if [[ -z "$branch" || -z "$flake_output" ]]; then
  echo "Acceptance branch and flake output must not be empty." >&2
  exit 2
fi

encoded_branch="$(jq -rn --arg value "$branch" '$value | @uri')"
flake_reference="git+$repository_url?ref=$encoded_branch#$flake_output"
printf 'Building acceptance workload %s#%s from branch %s\n' \
  "$repository_url" "$flake_output" "$branch"
nix build --refresh --no-link --print-build-logs "$flake_reference"
