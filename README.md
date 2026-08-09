# Nix Ship Android

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" alt="Nix Ship snowflake and ship mark">
</p>

[Nix Ship](https://github.com/imxade/nixship) for Android turns one ARM64 Android device into a
self-contained Nix Ship server and its management client. The APK installs Nix-on-Droid, starts the
bundled Nix Ship control plane inside a foreground service, and opens the loopback dashboard in a
hardened WebView. No separately hosted Nix Ship service is required.

This is a downstream fork of
[nix-community/nix-on-droid-app](https://github.com/nix-community/nix-on-droid-app), which in turn
contains GPLv3 Termux code. See [LICENSE.md](LICENSE.md) and [NOTICE.md](NOTICE.md).
Original Nix Ship-specific contributions and brand assets owned by Rituraj Basak are available under
Apache-2.0 where identified; inherited GPLv3 code remains GPLv3.

## How it works

At APK build time, the release workflow resolves the latest Nix Ship `master` commit from
`github.com/imxade/nixship`, builds and tests that revision, and exports the complete runtime
closure into `assets/control-plane`. At first launch:

1. the app verifies and installs the ABI-specific Nix-on-Droid bootstrap embedded by CI;
2. it verifies and streams the embedded closure into the app-private Nix store;
3. it creates the Nix Ship master key and wraps it with an Android Keystore AES-GCM key;
4. a foreground service executes the pinned, already-built Nix Ship store path directly;
5. the WebView waits for the loopback health endpoint, consumes the bounded one-time setup token,
   and opens the owner-account form;
6. application Quick Tunnel URLs under strict `*.trycloudflare.com` HTTPS origins can open in the
   same WebView.

The first start does not fetch the bootstrap, Nix Ship, Node.js, production Node modules, Nix, Git,
Cloudflared, or tar. They are already in the APK. Internet access is needed only for functions that
are inherently remote, such as cloning a workload repository and establishing a Quick Tunnel.
Later starts reuse the imported app-private Nix store.

## Preparing an application for Nix Ship

This Android project embeds the upstream [Nix Ship](https://github.com/imxade/nixship) control
plane, so application repositories must follow its
[deployment contract](https://github.com/imxade/nixship/blob/master/docs/DEPLOYMENT_CONTRACT.md).
A deployable repository must commit both `flake.nix` and `flake.lock` and expose the application as
an `apps.<system>` flake output.

Use the upstream
[`hello-flake`](https://github.com/imxade/nixship/tree/master/examples/hello-flake) and
[`npm-start-flake`](https://github.com/imxade/nixship/tree/master/examples/npm-start-flake)
examples as working templates. They also show how the application should remain in the foreground,
listen on the injected `HOST` and `PORT`, and store mutable data under `DATA_DIR`.

## Configuration

All product and external values live in [config/product.json](config/product.json), including:

- Android identity, version, SDK, build tools, NDK, and ABI;
- bootstrap source, per-ABI archive name and SHA-256, and embedded asset path;
- proot source and required Bionic TLS/page-size ELF alignments;
- Nix Ship repository, branch, exact revision, Nix source hash, and asset directory;
- server, Nix feature, WebView, and emulator resource limits;
- the Kitsy acceptance repository and expected UI text.

For local development, resolve the latest control-plane revision with:

```bash
nix develop --command scripts/update-control-plane.sh
```

This is optional for releases — the release workflow resolves the latest source automatically.

The supplied brand master is `art/nixship.svg`. Regenerate the Android launcher, TV banner,
and store-listing assets deterministically with:

```bash
nix develop --command scripts/generate-brand-assets.sh
```

## Build and test

```bash
nix flake check
nix develop --command shellcheck scripts/*.sh
nix develop --command gradle :app:testDebugUnitTest :app:lintDebug --no-daemon
nix build
nix develop --command scripts/verify-apk.sh \
  result/apk/release/nixship-android_release_arm64-v8a.apk
```

For repeatable local emulator coverage:

```bash
nix build .#emulator-apk --out-link result-emulator
nix develop .#emulator --command scripts/run-maestro-ci-emulator.sh \
  result-emulator/apk/debug/nixship-android_debug_x86_64.apk
```

The Nix emulator shell supplies the pinned SDK, API 35 system image, emulator, ADB, and Maestro.
The harness creates a disposable AVD with the configured 4 GB memory and 16 GB data partition.
For an already-running compatible x86_64 emulator, use `scripts/run-maestro-emulator.sh` directly.

For physical acceptance, attach an ARM64 device to a dedicated runner and use:

```bash
ANDROID_APK=/absolute/path/to/nixship-android-signed.apk \
  nix develop --command scripts/run-maestro-physical.sh
```

The physical harness clears this app’s data, installs the candidate, generates ephemeral account
credentials, exercises the complete UI journey, and records non-secret evidence and logcat output.

See [Architecture](docs/ARCHITECTURE.md), [Security](docs/SECURITY.md),
[Operations](docs/OPERATIONS.md), [Testing](docs/TESTING.md), and
[Releasing](docs/RELEASING.md).
