# Operations

## First launch

Use an ARM64 device on Android 8 or later. Keep at least 12 GB free before first deployment; the
embedded runtime is smaller, but trusted workload flakes can require several additional gigabytes
of Nix build inputs and outputs. The
first launch verifies and installs the checksum-pinned bootstrap embedded by CI, prepares its local
Nix launcher, and imports the CI-prepared runtime bundled in the APK. It does not fetch or build
Nix Ship, the bootstrap, or Node dependencies on the device. Import can still take tens of minutes
on slow flash or emulated storage; the app waits for up to the centrally configured startup timeout
and reports compressed-byte progress while the import remains active.

The startup screen continuously shows timestamped bootstrap, checksum, extraction, Nix import,
process, health-check, and control-plane output. It reports elapsed time and marks
startup as stalled when neither the runtime nor server has produced output for the configured
interval. If the native child exits before the launcher can redirect its logs, diagnostics retain
a centrally bounded, redacted tail of its stderr (or stdout) and identify signal-derived exits such
as `134 (signal 6, SIGABRT)`.

Use **Copy diagnostics** or **Share diagnostics** to export the visible report when startup fails.
The report includes app/control-plane provenance and device/ABI information, but redacts setup
tokens, authorization values, passwords, secrets, and master-key-shaped fields. Source logs remain
private app data under `~/.nix-platform/logs/`.

An immediate task exit with code 134 and no control-plane output indicates that a native launcher
aborted before the shell script ran. Release builds verify the bundled proot TLS and load-segment
alignment specifically to prevent this class of ARM64 Bionic failure.

Allow notifications so Android can show the required foreground-service notification. On first
launch, Nix Ship asks for the battery-optimization exemption before installing its bundled runtime.
For long-running hosting, allow that request, exempt Nix Ship from any additional OEM battery
controls, and keep the device powered and thermally stable.

## Data and logs

Runtime state is under the application’s private Termux home:

```text
$HOME/.nix-platform/
  data/
  runtime-import.verified
  logs/android-runtime.log
  logs/control-plane.log
```

Android app-data clearing or uninstalling removes accounts, deployments, Nix state, and the wrapped
master key. Treat those operations as destructive. Back up through an explicit, reviewed process;
Android system backup is disabled.

## Updates

An APK update with the same application ID and signing key preserves app-private data. A changed
runtime store path is imported and verified before it is executed. Existing store paths remain
available until an explicit Nix garbage-collection operation removes unreferenced paths.

The application ID changed from `com.termux.nix` to `com.termux.nixship` after
`v0.1.0-rc.16`. Android treats that as a separate installation, so an existing
`com.termux.nix` installation and its app-private state are not upgraded or migrated automatically.
Back up any state that must be retained before moving to the new application ID.

Source updates are intentional:

```bash
nix develop --command scripts/update-control-plane.sh
nix flake check
nix build
```

Review Nix Ship migrations and release notes before updating. The control plane handles forward-only
database migrations; downgrading the APK does not imply database rollback.

## Recovery

If the dashboard never becomes ready:

1. reopen Nix Ship and use Retry;
2. verify free space, foreground notification, and battery settings; verify network only for
   repository imports or Quick Tunnels;
3. inspect `adb logcat` and the app-private control-plane log on a controlled debug/test device;
4. reboot and reopen the app.

Do not clear app data as a troubleshooting shortcut unless loss of all hosted state is acceptable.
If a deployment fails, its retained stdout/stderr under `~/.nix-platform/data/logs/` shows the exact Nix
evaluation, download, build, process, or health-check failure.
