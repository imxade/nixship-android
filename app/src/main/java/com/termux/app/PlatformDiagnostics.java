package com.termux.app;

import android.content.Context;
import android.os.Build;

import com.termux.BuildConfig;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

final class PlatformDiagnostics {

    private static final Object LOG_LOCK = new Object();
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;]+"
    );
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
        "(?i)((?:password|passwd|token|secret|master[_-]?key)\\s*[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}&]+"
    );
    private static final Pattern SENSITIVE_QUERY = Pattern.compile(
        "(?i)([?&](?:token|password|secret|key)=)[^&#\\s]+"
    );

    private PlatformDiagnostics() {}

    static File runtimeRoot() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".nix-platform");
    }

    static File eventLog() {
        return new File(new File(runtimeRoot(), "logs"), "android-runtime.log");
    }

    static File controlPlaneLog() {
        return new File(new File(runtimeRoot(), "logs"), "control-plane.log");
    }

    static void record(Context context, String phase, String message) {
        String safePhase = oneLine(phase);
        String safeMessage = redact(oneLine(message), setupToken(context));
        String line = utcTimestamp(System.currentTimeMillis()) + " [" + safePhase + "] "
            + safeMessage + "\n";
        synchronized (LOG_LOCK) {
            File log = eventLog();
            File parent = log.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return;
            try {
                rotateIfNeeded(log, BuildConfig.DIAGNOSTICS_EVENT_LOG_MAX_BYTES);
                try (FileOutputStream output = new FileOutputStream(log, true)) {
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            } catch (IOException ignored) {
                // Diagnostics must never prevent startup.
            }
        }
    }

    static String snapshot(
        Context context,
        long startupStartedMillis,
        int healthAttempt,
        String healthResult
    ) {
        File events = eventLog();
        File server = controlPlaneLog();
        long latestProgress = Math.max(events.lastModified(), server.lastModified());
        long now = System.currentTimeMillis();
        long runningSeconds = startupStartedMillis <= 0
            ? 0
            : Math.max(0, (now - startupStartedMillis) / 1000L);
        long quietSeconds = latestProgress <= 0
            ? runningSeconds
            : Math.max(0, (now - latestProgress) / 1000L);
        boolean stalled = runningSeconds >= BuildConfig.DIAGNOSTICS_STALLED_SECONDS
            && quietSeconds >= BuildConfig.DIAGNOSTICS_STALLED_SECONDS;
        String token = setupToken(context);

        StringBuilder report = new StringBuilder();
        report.append("Nix Ship startup diagnostics\n")
            .append("Generated: ").append(utcTimestamp(now)).append('\n')
            .append("App: ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            .append("Control plane: ").append(BuildConfig.CONTROL_PLANE_REPOSITORY)
            .append('@').append(BuildConfig.CONTROL_PLANE_BRANCH).append('\n')
            .append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            .append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n')
            .append("ABIs: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n')
            .append("Startup elapsed: ").append(runningSeconds).append("s\n")
            .append("Health attempts: ").append(healthAttempt).append('\n')
            .append("Last health result: ").append(oneLine(healthResult)).append('\n')
            .append("Last runtime output: ").append(quietSeconds).append("s ago\n")
            .append("Progress state: ").append(stalled ? "STALLED - no new runtime output" : "active")
            .append("\n\nAndroid runtime events:\n")
            .append(readTail(events, BuildConfig.DIAGNOSTICS_DISPLAY_TAIL_BYTES))
            .append("\nControl-plane output:\n")
            .append(readTail(server, BuildConfig.DIAGNOSTICS_DISPLAY_TAIL_BYTES));
        return redact(report.toString(), token);
    }

    static String redact(String value, String setupToken) {
        if (value == null) return "";
        String redacted = AUTHORIZATION.matcher(value).replaceAll("$1<redacted>");
        redacted = SENSITIVE_FIELD.matcher(redacted).replaceAll("$1<redacted>");
        redacted = SENSITIVE_QUERY.matcher(redacted).replaceAll("$1<redacted>");
        if (setupToken != null && !setupToken.isEmpty()) {
            redacted = redacted.replace(setupToken, "<redacted>");
        }
        return redacted;
    }

    static String readTail(File file, int maximumBytes) {
        if (!file.isFile()) return "(no output yet)\n";
        long length = file.length();
        int count = (int) Math.min(Math.max(0, maximumBytes), length);
        byte[] data = new byte[count];
        try (FileInputStream input = new FileInputStream(file)) {
            long skip = length - count;
            while (skip > 0) {
                long skipped = input.skip(skip);
                if (skipped <= 0) break;
                skip -= skipped;
            }
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            String text = new String(data, 0, offset, StandardCharsets.UTF_8);
            int firstLine = length > count ? text.indexOf('\n') : -1;
            if (firstLine >= 0) text = text.substring(firstLine + 1);
            return text.endsWith("\n") ? text : text + "\n";
        } catch (IOException error) {
            return "(unable to read output: " + error.getClass().getSimpleName() + ")\n";
        }
    }

    static String processExitOutput(
        CharSequence stderr,
        CharSequence stdout,
        int maximumCharacters
    ) {
        CharSequence selected = stderr != null && stderr.length() > 0 ? stderr : stdout;
        if (selected == null || selected.length() == 0 || maximumCharacters <= 0) return null;
        String output = oneLine(selected.toString()).trim();
        if (output.isEmpty()) return null;
        if (output.length() <= maximumCharacters) return output;
        return "…" + output.substring(output.length() - maximumCharacters + 1);
    }

    static String describeExit(Integer exitCode) {
        if (exitCode == null) return "an unavailable exit code";
        if (exitCode < 128 || exitCode > 255) return "code " + exitCode;
        int signal = exitCode - 128;
        return "code " + exitCode + " (signal " + signal
            + (signal == 6 ? ", SIGABRT" : "") + ")";
    }

    private static void rotateIfNeeded(File log, int maximumBytes) throws IOException {
        if (!log.isFile() || log.length() < maximumBytes) return;
        String tail = readTail(log, maximumBytes / 2);
        try (FileOutputStream output = new FileOutputStream(log, false)) {
            output.write(tail.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String setupToken(Context context) {
        File token = new File(new File(runtimeRoot(), "data"), "setup-token.txt");
        return PlatformSetupToken.read(
            token,
            BuildConfig.SETUP_TOKEN_MIN_CHARACTERS,
            BuildConfig.SETUP_TOKEN_MAX_BYTES
        );
    }

    private static String oneLine(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ').replace('\0', '?');
    }

    private static String utcTimestamp(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestamp));
    }
}
