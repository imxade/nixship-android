# Security

## Secrets

The Nix Ship master key is generated with `SecureRandom`, wrapped by an Android Keystore AES/GCM key,
and stored only as ciphertext in private preferences. The plaintext is supplied to the Nix Ship
process through its environment and is never returned through the UI or written to the launcher
script. If the wrapping key disappears while ciphertext remains, the app fails closed instead of
silently replacing the server identity.

APK signing keys exist only as GitHub Actions secrets or local ignored files. Release signing occurs
outside Nix derivations so the keystore and passwords cannot enter the world-readable Nix store.
Maestro writes its transient debug stream outside retained evidence. The acceptance wrappers redact
both generated passwords and setup claim tokens before copying debug data into the artifact
directory, so an abrupt test-process termination does not upload an unredacted debug directory.

## Supply chain

- The ABI-specific bootstrap is fetched over HTTPS by Nix at build time, content-addressed,
  embedded in the APK, then size-bounded, SHA-256 verified, and extracted through canonical path
  checks on device.
- Nix Ship source is fetched by exact Git revision and Nix hash, built with its locked Nixpkgs, and
  tested before packaging.
- The APK runtime manifest pins the Nix system, store path, compressed size, and SHA-256. Import is
  streamed without a writable archive copy and startup fails if verification does not complete.
- Gradle artifacts are described by `nix/gradle.lock`.
- GitHub Actions are pinned to full commit SHAs.
- Releases include SHA-256 sums, a CycloneDX SBOM, and the non-secret build configuration.

## Android surface

Only the launcher activity is exported. The foreground service, boot receiver, internal terminal
activity, and legacy receivers are unexported. Backup is disabled. The application requests no
storage, camera, microphone, location, contacts, or account permission.

The WebView disables file/content access, mixed content, geolocation, third-party cookies,
automatic JavaScript windows, and JavaScript bridges. Safe Browsing is enabled. Loopback cleartext
is scoped by the network security configuration; remote application access requires HTTPS.

## Trusted workload model

Nix Ship executes imported application flakes. Those repositories are trusted code with the same
Nix-on-Droid user authority as the control plane; this is not a sandbox or multi-tenant boundary.
Deploy only repositories you control and review.

Quick Tunnels are public, temporary URLs. Applications must implement their own authentication when
public access needs protection.
