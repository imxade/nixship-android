#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
gradle_lock_file="$repository_root/nix/gradle.lock"
apk_path="${1:-}"
output_path="${2:-}"

if [[ -z "$apk_path" || ! -f "$apk_path" || -z "$output_path" ]]; then
  echo "Usage: scripts/generate-sbom.sh nixship-android.apk output.cdx.json" >&2
  exit 2
fi

for command in jq sha256sum syft unzip; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run through nix develop." >&2
    exit 2
  fi
done

apk_path="$(realpath "$apk_path")"
output_parent="$(dirname "$output_path")"
mkdir -p "$output_parent"
output_path="$(realpath "$output_parent")/$(basename "$output_path")"
working_directory="$(mktemp -d)"
cleanup_working_directory() {
  rm -r -- "$working_directory"
}
trap cleanup_working_directory EXIT

unzip -q "$apk_path" -d "$working_directory/apk"
version_name="$(jq -r '.android.versionName' "$configuration_file")"
application_id="$(jq -r '.android.applicationId' "$configuration_file")"
architecture="$(jq -r '.android.abi' "$configuration_file")"
apk_sha256="$(sha256sum "$apk_path" | awk '{print $1}')"
bootstrap_asset_directory="$(jq -r '.bootstrap.assetDirectory' "$configuration_file")"
bootstrap_asset_name="$(jq -r '.bootstrap.assetName' "$configuration_file")"
bootstrap_archive_name="$(jq -r --arg abi "$architecture" \
  '.bootstrap.archiveNameByAndroidAbi[$abi]' "$configuration_file")"
bootstrap_sha256="$(jq -r --arg abi "$architecture" \
  '.bootstrap.embeddedSha256ByAndroidAbi[$abi]' "$configuration_file")"
bootstrap_base_url="$(jq -r '.bootstrap.baseUrl' "$configuration_file")"
proot_repository="https://github.com/$(jq -r '.proot.repositoryOwner' "$configuration_file")/$(jq -r '.proot.repositoryName' "$configuration_file")"
proot_revision="$(jq -r '.proot.revision' "$configuration_file")"
proot_source_hash="$(jq -r '.proot.sourceHash' "$configuration_file")"
talloc_url="$(jq -r '.proot.tallocUrl' "$configuration_file")"
talloc_hash="$(jq -r '.proot.tallocHash' "$configuration_file")"
bootstrap_path="$working_directory/apk/assets/$bootstrap_asset_directory/$bootstrap_asset_name"
if [[ ! -f "$bootstrap_path" ]] ||
  [[ "$(sha256sum "$bootstrap_path" | awk '{print $1}')" != "$bootstrap_sha256" ]]; then
  echo "APK bootstrap asset is missing or does not match its configured SHA-256." >&2
  exit 1
fi
asset_directory="$(jq -r '.controlPlane.assetDirectory' "$configuration_file")"
runtime_manifest="$working_directory/apk/assets/$asset_directory/runtime.json"
if ! jq -e '.schemaVersion == 1 and (.archiveSha256 | test("^[0-9a-f]{64}$"))' \
  "$runtime_manifest" >/dev/null; then
  echo "APK runtime manifest is missing or invalid." >&2
  exit 1
fi

syft scan "dir:$working_directory/apk" \
  --source-name nixship-android-apk \
  --source-version "$version_name" \
  -o "cyclonedx-json=$working_directory/syft.cdx.json"

jq \
  --slurpfile gradleLock "$gradle_lock_file" \
  --arg applicationId "$application_id" \
  --arg architecture "$architecture" \
  --arg version "$version_name" \
  --arg apkSha256 "$apk_sha256" \
  --arg bootstrapArchiveName "$bootstrap_archive_name" \
  --arg bootstrapAsset "$bootstrap_asset_directory/$bootstrap_asset_name" \
  --arg bootstrapBaseUrl "$bootstrap_base_url" \
  --arg bootstrapSha256 "$bootstrap_sha256" \
  --arg prootRepository "$proot_repository" \
  --arg prootRevision "$proot_revision" \
  --arg prootSourceHash "$proot_source_hash" \
  --arg tallocUrl "$talloc_url" \
  --arg tallocHash "$talloc_hash" \
  --slurpfile runtime "$runtime_manifest" \
  '
    def maven_component:
      split(":") as $coordinate |
      if ($coordinate | length) != 3 then
        error("Invalid Gradle lock coordinate: \(.)")
      else
        {
          type: "library",
          group: $coordinate[0],
          name: $coordinate[1],
          version: $coordinate[2],
          purl: (
            "pkg:maven/"
            + ($coordinate[0] | @uri)
            + "/"
            + ($coordinate[1] | @uri)
            + "@"
            + ($coordinate[2] | @uri)
          )
        }
        | .["bom-ref"] = .purl
      end;

    def runtime_component:
      {
        type: "application",
        name: "nixship-control-plane",
        version: $runtime[0].revision,
        "bom-ref": $runtime[0].storePath,
        hashes: [{alg: "SHA-256", content: $runtime[0].archiveSha256}],
        properties: [
          {name: "nixship:repository", value: $runtime[0].repository},
          {name: "nixship:branch", value: $runtime[0].branch},
          {name: "nixship:system", value: $runtime[0].system},
          {name: "nixship:store-path", value: $runtime[0].storePath},
          {name: "nixship:archive-bytes", value: ($runtime[0].archiveBytes | tostring)}
        ]
      };

    def bootstrap_component:
      {
        type: "framework",
        name: "nix-on-droid-bootstrap",
        version: $bootstrapArchiveName,
        "bom-ref": ("nixship:bootstrap:" + $architecture + ":" + $bootstrapSha256),
        hashes: [{alg: "SHA-256", content: $bootstrapSha256}],
        properties: [
          {name: "nixship:architecture", value: $architecture},
          {name: "nixship:asset", value: $bootstrapAsset},
          {name: "nixship:source", value: ($bootstrapBaseUrl + "/" + $bootstrapArchiveName)}
        ]
      };

    def proot_component:
      {
        type: "application",
        name: "proot-termux-static",
        version: $prootRevision,
        "bom-ref": ("nixship:proot:" + $architecture + ":" + $prootRevision),
        properties: [
          {name: "nixship:architecture", value: $architecture},
          {name: "nixship:repository", value: $prootRepository},
          {name: "nixship:nix-source-hash", value: $prootSourceHash},
          {name: "nixship:embedded-in", value: $bootstrapAsset}
        ]
      };

    def talloc_component:
      {
        type: "library",
        name: "talloc",
        version: "2.4.2",
        "bom-ref": ("nixship:talloc:" + $tallocHash),
        properties: [
          {name: "nixship:source", value: $tallocUrl},
          {name: "nixship:nix-source-hash", value: $tallocHash},
          {name: "nixship:linked-into", value: "proot-termux-static"}
        ]
      };

    .metadata.component = {
      type: "application",
      name: $applicationId,
      version: $version,
      hashes: [{alg: "SHA-256", content: $apkSha256}],
      properties: [
        {name: "nixship:artifact", value: "Android APK"},
        {name: "nixship:architecture", value: $architecture}
      ]
    }
    | .components = (
        (
          (.components // [])
          + [bootstrap_component, proot_component, talloc_component, runtime_component]
          + ($gradleLock[0] | keys | map(maven_component))
        )
        | sort_by(.["bom-ref"])
        | unique_by(.["bom-ref"])
      )
    | if (.components | length) == 0 then
        error("Generated SBOM has no components")
      else
        .
      end
  ' "$working_directory/syft.cdx.json" >"$output_path"

jq -e '
  .bomFormat == "CycloneDX" and
  .metadata.component.type == "application" and
  (.components | length > 0)
' "$output_path" >/dev/null
printf 'Generated CycloneDX SBOM with %s components\n' \
  "$(jq '.components | length' "$output_path")"
