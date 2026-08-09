# Repository engineering guide

## Read first

1. `README.md`
2. `config/product.json`
3. `docs/ARCHITECTURE.md`
4. `docs/SECURITY.md`
5. `docs/TESTING.md`
6. affected source and tests

## Non-negotiable constraints

- Nix flakes are the normal dependency, build, and test contract.
- Keep all product/external values in `config/product.json`; do not duplicate repository URLs,
  revisions, ports, SDK versions, ABI names, or bootstrap hashes in source or automation.
- Keep the Platform control plane embedded in the APK. Do not introduce a separately hosted backend.
- Never persist the plaintext Platform master key or include signing material in source, artifacts,
  logs, Nix derivations, or the Nix store.
- Never enable arbitrary WebView origins, file/content access, JavaScript bridges, mixed content, or
  SSL-error bypasses.
- Keep services, receivers, and activities unexported unless the launcher or Android platform
  contract explicitly requires export.
- Treat deployed workload repositories as trusted code, not an isolation boundary.
- Do not claim production or physical-device compatibility from emulator-only evidence.

## Required checks

```bash
nix flake check
nix develop --command shellcheck scripts/*.sh
nix develop --command gradle :app:testDebugUnitTest :app:lintDebug --no-daemon
nix build
nix develop --command scripts/verify-apk.sh \
  result/apk/release/nixship-android_release_arm64-v8a.apk
```

Changes to runtime, lifecycle, bootstrap, WebView, deployment, or release behavior also require the
physical ARM64 Maestro workflow. Stable tags require that workflow on the exact tagged commit.
