# Releasing

## GitHub secrets

The private repository requires:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_STORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Never place these values in Nix arguments, derivations, artifacts, logs, or repository files.

## Candidate release

Open the **Actions** tab → **Release** → **Run workflow**. Fill in:

| Input | Example | Notes |
|-------|---------|-------|
| `version_name` | `0.1.0-rc.15` | Valid semver; a hyphenated pre-release suffix marks the release automatically |
| `version_code` | `15` | Positive integer; must exceed every prior release |
| `release_notes` | _(optional)_ | Appended before the auto-generated provenance table |
| `prerelease` | `true` | Auto-detected from `version_name` when left at default |

The workflow resolves the latest `master` HEAD of `github.com/imxade/nixship` at build time. No file
changes or commits are required to start a release.

The release workflow:

1. Validates inputs and checks that the tag `v<version_name>` does not already exist.
2. Builds and tests Nix Ship on a native ARM64 hosted runner, exports that exact closure.
3. Verifies/imports the closure on the Android builder.
4. Patches `product.json` in the CI workspace with the resolved revision, source hash, and version.
5. Builds the unsigned APK hermetically with Nix.
6. Signs outside the Nix store, verifies the result, generates checksums.
7. Creates the tag and GitHub release with all artifacts attached.

The CycloneDX SBOM records the embedded bootstrap and Nix Ship runtime, catalogs embedded JavaScript
dependencies, and merges all Maven coordinates pinned by `nix/gradle.lock`; generation fails if any
required embedded component is missing.

## Local development

To manually resolve and test the latest control plane locally:

```bash
nix develop --command scripts/update-control-plane.sh
```

This updates `config/product.json` with the latest revision/hash so local `nix build` commands use
the current source. This is for development only; releases do not use the locally committed values.

## Stable release gate

A stable tag such as `v0.1.0` is rejected unless the exact commit already has two successful
`Physical ARM64 acceptance` workflow runs whose uploaded evidence identifies two different device
manufacturers.

## Post-release verification

After the workflow completes, verify:

- the release is prerelease/stable as expected;
- the APK signature certificate is unchanged from the prior release;
- `SHA256SUMS` matches downloaded assets;
- `build-provenance.json` names the intended Nix Ship revision;
- the APK installs over the preceding build without clearing data.
