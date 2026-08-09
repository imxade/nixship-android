# Architecture

## Build-time runtime embedding

`config/product.json` declares the Nix Ship repository, the `master` branch, the exact resolved
revision, and its Nix hash. `flake.nix` uses `fetchgit`, the control plane’s own locked Nixpkgs, and
the upstream Nix package to build and test that revision. The full runtime closure is exported as:

```text
app/src/main/assets/control-plane/runtime.nar.bundle
app/src/main/assets/control-plane/runtime.json
```

The manifest records repository, branch, revision, target Nix system, root store path, compressed
size, and SHA-256. ARM64 production and x86_64 emulator closures are separate flake outputs. This
avoids Git operations, dependency downloads, source compilation, or a moving branch at Android
runtime.

## Runtime

The launcher activity verifies/installs the pinned ABI-specific Nix-on-Droid bootstrap embedded in
the APK, replaces the upstream network initializer with a minimal local launcher, then starts the
unexported `TermuxService` as an Android special-use foreground service. The service:

- validates bundle provenance and device architecture before import;
- streams the compressed asset through a private FIFO to `nix-store --import`;
- verifies the compressed byte count and SHA-256 before accepting the import;
- creates app-private data and log directories;
- injects the Keystore-unwrapped master key only into the child process environment;
- supplies the centrally configured `nix-command` and `flakes` feature set to the control plane and
  its workload processes;
- executes the manifest’s exact `/nix/store/.../bin/nixship` path.

The control plane binds to the configured LAN address and port. It is the server; the Android
application does not depend on another Nix Ship deployment. Android supplies its Wi-Fi IPv4 address
to the control plane for LAN links. The unsafe Node/libuv interface probe is disabled because the
Android sandbox can deny or crash that native operation.

The APK intentionally targets Android API 28, matching Nix-on-Droid’s executable app-data model.
It compiles against API 35. Raising the target SDK without a replacement execution architecture
causes current Android SELinux policy to reject the user-space Nix executables. The app is therefore
a private sideloaded distribution, not a Play Store artifact.

The flake rebuilds the bundled proot executable with a 64-byte-aligned ARM64 TLS segment and
16 KiB-aligned load segments. Both the proot derivation and final APK verifier reject weaker ELF
alignment. ARM64 Bionic otherwise aborts the process before the runtime launcher can produce
control-plane output.

## Client

`PlatformActivity` polls the configured loopback health endpoint off the UI thread. When healthy, it
reads the one-time setup token from app-private Nix Ship data with no-follow metadata checks, strict
URL-safe syntax, and configured size bounds. It then opens the claim URL in a hardened WebView.

In-app top-level navigation is limited to:

- the exact configured HTTP loopback host and port;
- HTTPS hosts that are strict subdomains of the configured Quick Tunnel suffix;
- `https://github.com` (exact host) — required for the GitHub App Manifest creation flow, which
  submits the manifest as a form POST that an external browser intent would lose.

Other HTTP(S) URLs are offered to an external browser. Other schemes are ignored. SSL errors and
HTTP authentication requests are never bypassed.

## Lifecycle limits

The service is sticky and the boot receiver attempts restoration after a normal reboot. Android
force-stop, OEM battery killing, thermal/resource limits, and missing kernel capabilities remain
platform constraints. Foreground-service status improves survivability but is not a daemon
guarantee.
