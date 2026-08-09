package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.system.Os;

import com.termux.BuildConfig;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

final class PlatformRuntime {

    static final String ACTION_START = BuildConfig.APPLICATION_ID + ".action.START_PLATFORM";
    private static final String LOG_TAG = "PlatformRuntime";
    private static final String SHELL_NAME = "control-plane";

    private PlatformRuntime() {}

    static Intent startIntent(Context context) {
        return new Intent(context, TermuxService.class).setAction(ACTION_START);
    }

    static void start(TermuxService service) throws Exception {
        PlatformDiagnostics.record(service, "RUNTIME", "Foreground service entered");
        File login = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "login");
        if (!isBootstrapReady(login)) {
            PlatformDiagnostics.record(
                service,
                "BOOTSTRAP",
                "Runtime service deferred until the verified bootstrap installation completes"
            );
            return;
        }
        PlatformDiagnostics.record(service, "ASSETS", "Verifying the bundled runtime manifest");
        PlatformAssets.RuntimeBundle bundle = PlatformAssets.loadRuntimeBundle(service);
        File runtimeRoot = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".nix-platform");
        File dataDirectory = new File(runtimeRoot, "data");
        File logDirectory = new File(runtimeRoot, "logs");
        requireDirectory(dataDirectory);
        requireDirectory(logDirectory);
        PlatformDiagnostics.record(
            service,
            "ASSETS",
            "Verified " + bundle.system + " runtime manifest for " + bundle.storePath
        );

        HashMap<String, String> environment = new HashMap<>();
        environment.put("PLATFORM_MASTER_KEY", PlatformKeystore.getOrCreateMasterKey(service));
        environment.put("PLATFORM_DATA_DIR", dataDirectory.getAbsolutePath());
        environment.put("HOSTNAME", BuildConfig.SERVER_BIND_ADDRESS);
        environment.put("PORT", String.valueOf(BuildConfig.SERVER_PORT));
        environment.put("USE_FLAKE", "1");
        environment.put("NIX_CONFIG", BuildConfig.NIX_CONFIG);
        environment.put(
            "DISABLE_LAN_DISCOVERY",
            BuildConfig.ALLOW_NODE_INTERFACE_DISCOVERY ? "0" : "1"
        );
        String lanAddress = wifiIpv4Address(service);
        if (lanAddress != null) {
            environment.put("LAN_ADDRESS", lanAddress);
            PlatformDiagnostics.record(service, "LAN", "Android reported LAN address " + lanAddress);
        } else if (!BuildConfig.ALLOW_NODE_INTERFACE_DISCOVERY) {
            PlatformDiagnostics.record(
                service,
                "LAN",
                "Android did not report a Wi-Fi address; unsafe Node interface discovery is disabled"
            );
        }

        startControlPlaneTask(service, runtimeRoot, bundle, logDirectory, environment);
    }

    static boolean isBootstrapReady(File login) {
        return login != null && login.isFile() && login.canExecute();
    }

    private static String wifiIpv4Address(Context context) {
        try {
            WifiManager manager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (manager == null || manager.getConnectionInfo() == null) return null;
            return formatWifiIpv4(manager.getConnectionInfo().getIpAddress());
        } catch (RuntimeException error) {
            Logger.logWarn(LOG_TAG, "Android Wi-Fi address discovery failed: " + error.getMessage());
            return null;
        }
    }

    static String formatWifiIpv4(int address) {
        if (address == 0) return null;
        int first = address & 0xff;
        if (first == 0 || first == 127 || first >= 224) return null;
        return first + "."
            + ((address >>> 8) & 0xff) + "."
            + ((address >>> 16) & 0xff) + "."
            + ((address >>> 24) & 0xff);
    }

    private static void startControlPlaneTask(
        TermuxService service,
        File runtimeRoot,
        PlatformAssets.RuntimeBundle bundle,
        File logDirectory,
        HashMap<String, String> baseEnvironment
    ) throws Exception {
        if (service.getTermuxTaskForShellName(SHELL_NAME) != null) {
            PlatformDiagnostics.record(service, "SERVER", "Control-plane task is already running");
            return;
        }
        boolean importRequired = !PlatformAssets.isRuntimeInstalled(bundle);
        File importPipe = new File(runtimeRoot, "runtime-import.pipe");
        File verifiedMarker = new File(runtimeRoot, "runtime-import.verified");
        if (importRequired) {
            deletePrivateFile(importPipe);
            deletePrivateFile(verifiedMarker);
            Os.mkfifo(importPipe.getAbsolutePath(), 0600);
            PlatformDiagnostics.record(
                service,
                "IMPORT",
                "Importing " + bundle.archiveBytes + " compressed bytes into the Nix store"
            );
        } else {
            PlatformDiagnostics.record(
                service,
                "IMPORT",
                "Prepared runtime is already present; no import is needed"
            );
        }
        File launcher = writeLauncher(
            runtimeRoot,
            bundle,
            logDirectory,
            importPipe,
            verifiedMarker
        );
        PlatformDiagnostics.record(service, "SERVER", "Launching bundled Nix Ship process");
        HashMap<String, String> environment = new HashMap<>(baseEnvironment);
        environment.put("BASH_ENV", launcher.getAbsolutePath());
        service.startPlatformTask(
            SHELL_NAME,
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login",
            null,
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            environment
        );
        if (importRequired) {
            new Thread(
                () -> streamRuntimeBundle(service, bundle, importPipe, verifiedMarker),
                "nix-runtime-importer"
            ).start();
        }
    }

    private static File writeLauncher(
        File runtimeRoot,
        PlatformAssets.RuntimeBundle bundle,
        File logDirectory,
        File importPipe,
        File verifiedMarker
    ) throws Exception {
        File launcher = new File(runtimeRoot, "launch-control-plane");
        String script = buildLauncherScript(bundle, logDirectory, importPipe, verifiedMarker);
        try (FileOutputStream output = new FileOutputStream(launcher, false)) {
            output.write(script.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Os.chmod(launcher.getAbsolutePath(), 0700);
        return launcher;
    }

    static String buildLauncherScript(
        PlatformAssets.RuntimeBundle bundle,
        File logDirectory,
        File importPipe,
        File verifiedMarker
    ) {
        return "set -euo pipefail\n"
            + "unset BASH_ENV\n"
            + "umask 077\n"
            + "exec >>" + shellQuote(logDirectory.getAbsolutePath() + "/control-plane.log")
            + " 2>&1\n"
            + "runtime=" + shellQuote(bundle.storePath) + "\n"
            + "if [[ ! -x \"$runtime/bin/nixship\" ]]; then\n"
            + "  echo \"[android] importing CI-prepared Nix Ship runtime\"\n"
            + "  nix-store --import < " + shellQuote(importPipe.getAbsolutePath()) + "\n"
            + "  for ((attempt = 0; attempt < 120; attempt++)); do\n"
            + "    [[ -f " + shellQuote(verifiedMarker.getAbsolutePath()) + " ]] && break\n"
            + "    sleep 1\n"
            + "  done\n"
            + "  [[ -f " + shellQuote(verifiedMarker.getAbsolutePath()) + " ]] || {\n"
            + "    echo \"[android] runtime archive verification did not complete\" >&2\n"
            + "    exit 65\n"
            + "  }\n"
            + "fi\n"
            + "program=\"$runtime/bin/nixship\"\n"
            + "[[ -x \"$program\" ]] || {\n"
            + "  echo \"[android] imported runtime executable is missing\" >&2\n"
            + "  exit 66\n"
            + "}\n"
            + "echo \"[android] starting CI-prepared Nix Ship control plane\"\n"
            + "exec \"$program\"\n";
    }

    private static void streamRuntimeBundle(
        TermuxService service,
        PlatformAssets.RuntimeBundle bundle,
        File importPipe,
        File verifiedMarker
    ) {
        try {
            PlatformAssets.streamRuntimeArchive(service, bundle, importPipe);
            try (FileOutputStream output = new FileOutputStream(verifiedMarker, false)) {
                output.write(bundle.manifest.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Os.chmod(verifiedMarker.getAbsolutePath(), 0600);
            PlatformDiagnostics.record(
                service,
                "IMPORT",
                "Runtime archive checksum passed and the Nix import stream completed"
            );
        } catch (Exception error) {
            PlatformDiagnostics.record(
                service,
                "ERROR",
                "Runtime import failed: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage()
            );
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to import bundled runtime", error);
        } finally {
            if (importPipe.exists() && !importPipe.delete()) {
                Logger.logWarn(LOG_TAG, "Unable to remove the runtime import pipe");
            }
        }
    }

    private static void deletePrivateFile(File file) {
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Unable to replace " + file);
        }
    }

    static String shellQuote(String value) {
        if (value.indexOf('\0') >= 0 || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("Runtime path contains unsupported characters");
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void requireDirectory(File directory) {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create " + directory);
        }
    }
}
