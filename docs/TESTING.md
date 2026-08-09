# Testing

## Host checks

Run all commands through the flake:

```bash
nix flake check
nix develop --command shellcheck scripts/*.sh
nix develop --command gradle :app:testDebugUnitTest :app:lintDebug --no-daemon
nix build
nix develop --command scripts/verify-apk.sh \
  result/apk/release/nixship-android_release_arm64-v8a.apk
nix develop --command scripts/generate-sbom.sh \
  result/apk/release/nixship-android_release_arm64-v8a.apk \
  /tmp/nixship-android.cdx.json
nix develop --command scripts/verify-acceptance-workload.sh
```

Focused tests cover strict loopback/Quick Tunnel navigation, bounded no-follow setup-token handling,
symlink-safe asset cleanup, archive traversal rejection, shell-safe runtime launch arguments, and
diagnostic tail bounding/credential redaction.
The APK verifier checks package ID, version, min/target SDK, expected native ABI, zip alignment,
the embedded bootstrap SHA-256/size, runtime manifest provenance, and the embedded runtime archive
SHA-256/size. It also extracts the bundled proot binary and verifies its configured TLS and ELF
load-segment alignment. The SBOM generator must find components from the embedded bootstrap, control-plane
runtime, and hermetic Gradle lock.

## Maestro emulator journey

The x86_64 output is a signed debug-only APK with an independently pinned x86_64 Nix-on-Droid
bootstrap and Nix Ship closure. It runs the same full journey and first asserts that exact startup
diagnostics are visible. Emulator evidence is never accepted as production evidence.

```bash
nix build .#emulator-apk --out-link result-emulator
nix develop .#emulator --command scripts/run-maestro-ci-emulator.sh \
  result-emulator/apk/debug/nixship-android_debug_x86_64.apk
```

The CI wrapper creates a disposable API 35 x86_64 AVD using the centrally configured 4 GB memory
and 16 GB data partition. CI pins the lean emulator shell in a Nix profile and removes build-only
store paths before boot so that the configured data partition is not silently reduced. The
preflight evidence records the Java runtime, host capacity, and AVD creation output; `emulator.log`
retains emulator startup failures. The lower-level wrapper only accepts a connected x86_64
emulator. The disposable CI AVD disables optional Google background packages that otherwise produce
unrelated ANR dialogs under heavy I/O and suppresses system error overlays; the journey separately
fails if logcat contains a Nix Ship ANR. On exit it records JUnit/debug output, logcat,
service/package state, startup logs, and workload logs.
While Maestro runs, CI prints the newest redacted runtime event, deployment event, and non-input
Maestro command at the centrally configured interval. On failure it prints bounded, redacted tails
of the JUnit report, control-plane log, workload logs, and Android runtime log. It never prints input
text, setup tokens, the Platform master key, or an unredacted control-plane log. CI uploads the full
redacted evidence directory as a downloadable GitHub Actions artifact. If artifact storage is
unavailable or over quota, the upload is non-fatal and CI preserves the directory in a run-specific
Actions cache instead.
The first-run assertion uses the same centrally configured startup bound as the application because
the real bundled Nix-store import can take tens of minutes on hosted-runner emulated storage.
Maestro debug data is written outside the artifact directory while the test runs; generated
passwords and setup claim tokens are redacted before that data is copied into retained evidence.

## Maestro physical journey

`maestro/flows/full-journey.yaml` performs:

1. one-time device claim and owner creation;
2. sign out and login with the created credentials;
3. password change;
4. rejection of the old password and login with the new password;
5. import of the configured Kitsy repository and `nixhost` branch;
6. wait for a running deployment and Temporary public URL;
7. open the Quick Tunnel in the app and assert Kitsy’s configured ready text.

The wrapper accepts only a non-emulated `arm64-v8a` device, validates the APK before installation,
generates ephemeral credentials, clears only the configured app package, and records device/APK
evidence without passwords.

```bash
ANDROID_APK=/absolute/path/to/nixship-android-signed.apk \
  nix develop --command scripts/run-maestro-physical.sh
```

Production acceptance requires successful runs on two physical ARM64 devices from different OEMs.
Record Android version, fingerprint, battery configuration, test report, APK SHA-256, and embedded
Nix Ship revision. Force-stop recovery cannot be promised because Android explicitly suppresses
force-stopped applications until the user launches them.
