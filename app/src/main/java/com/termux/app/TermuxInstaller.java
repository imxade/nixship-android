package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.BuildConfig;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (3) The APK's ABI-specific, checksum-pinned bootstrap asset is opened.
 * <p/>
 * (4) The zip, containing entries relative to the $PREFIX, is verified and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";
    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        setupBundledBootstrapIfNeeded(activity, whenDone);
    }

    /** Installs the release-pinned Nix-on-Droid bootstrap without accepting a user URL. */
    static void setupPlatformBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        setupBundledBootstrapIfNeeded(activity, whenDone);
    }

    private static void setupBundledBootstrapIfNeeded(
        final Activity activity,
        final Runnable whenDone
    ) {
        PlatformDiagnostics.record(activity, "BOOTSTRAP", "Checking the pinned Nix-on-Droid runtime");
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                PlatformDiagnostics.record(activity, "BOOTSTRAP", "Existing Nix-on-Droid runtime found");
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        restOfSetupIfNeeded(activity, whenDone);
    }

    static void restOfSetupIfNeeded(
        final Activity activity,
        final Runnable whenDone
    ) {
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");
                    PlatformDiagnostics.record(
                        activity,
                        "BOOTSTRAP",
                        "Installing bundled pinned runtime asset "
                            + BuildConfig.BOOTSTRAP_ASSET
                    );

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);
                    final List<String> executables = new ArrayList<>(128);

                    File downloadedZip = loadBundledBootstrap(
                        activity,
                        BuildConfig.BOOTSTRAP_ASSET,
                        BuildConfig.BOOTSTRAP_SHA256
                    );
                    PlatformDiagnostics.record(
                        activity,
                        "BOOTSTRAP",
                        "Extracting verified archive (" + downloadedZip.length() + " bytes)"
                    );
                    try (ZipInputStream zipInput = new ZipInputStream(new FileInputStream(downloadedZip))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = safeStagingPath(parts[1]).getAbsolutePath();
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(
                                            activity,
                                            whenDone,
                                            Error.getErrorMarkdownString(error)
                                        );
                                        return;
                                    }
                                }
                            } else if (zipEntry.getName().equals("EXECUTABLES.txt")) {
                                BufferedReader executablesReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = executablesReader.readLine()) != null) {
                                    executables.add(line);
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = safeStagingPath(zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(
                                        activity,
                                        whenDone,
                                        Error.getErrorMarkdownString(error)
                                    );
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    } finally {
                        if (!downloadedZip.delete()) {
                            Logger.logWarn(LOG_TAG, "Unable to delete downloaded bootstrap archive");
                        }
                    }

                    if (!executables.isEmpty()) {
                        for (String executable : executables) {
                            //noinspection OctalInteger
                            try {
                                Os.chmod(safeStagingPath(executable).getAbsolutePath(), 0700);
                            } catch (Throwable t) {
                                Logger.logError(LOG_TAG, "Invalid EXECUTABLES entry: " + executable + ": " + t);
                            }
                        }
                    } else {
                        throw new RuntimeException("Installer: no EXECUTABLES.txt found while extracting environment archive.");
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }
                    prepareBundledRuntimeLauncher();

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                    PlatformDiagnostics.record(
                        activity,
                        "BOOTSTRAP",
                        "Nix-on-Droid runtime installed successfully"
                    );

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    PlatformDiagnostics.record(
                        activity,
                        "ERROR",
                        "Bootstrap failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                    );
                    showBootstrapErrorDialog(activity, whenDone,
                        Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));
                }
            }
        }.start();
    }

    private static void showBootstrapErrorDialog(
        Activity activity,
        Runnable whenDone,
        String message
    ) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.restOfSetupIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    private static void prepareBundledRuntimeLauncher() throws Exception {
        File uninitialised = safeStagingPath("etc/UNINTIALISED");
        if (Files.exists(uninitialised.toPath(), LinkOption.NOFOLLOW_LINKS)
            && !uninitialised.delete()) {
            throw new IllegalStateException("Unable to disable the network Nix-on-Droid initializer");
        }

        File nixStoreBinary = findNixStoreBinary(
            safeStagingPath("nix/store")
        );
        File stableNixStoreBinary = safeStagingPath("bin/nix-store");
        if (Files.exists(stableNixStoreBinary.toPath(), LinkOption.NOFOLLOW_LINKS)
            && !stableNixStoreBinary.delete()) {
            throw new IllegalStateException("Unable to replace the bootstrap nix-store launcher");
        }
        String prootTarget = "/nix/store/" + nixStoreBinary.getParentFile().getParentFile().getName()
            + "/bin/nix-store";
        Os.symlink(prootTarget, stableNixStoreBinary.getAbsolutePath());

        File loginInner = safeStagingPath("usr/lib/login-inner");
        String launcher = "set -euo pipefail\n"
            + "export HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH + "\n"
            + "export USER=nix-on-droid\n"
            + "export PATH=/bin:/usr/bin\n"
            + "export NIX_SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt\n"
            + "exec -a bash /bin/sh\n";
        try (FileOutputStream output = new FileOutputStream(loginInner, false)) {
            output.write(launcher.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Os.chmod(loginInner.getAbsolutePath(), 0700);
    }

    static File findNixStoreBinary(File storeDirectory) {
        File[] storePaths = storeDirectory == null ? null : storeDirectory.listFiles();
        File result = null;
        if (storePaths != null) {
            for (File storePath : storePaths) {
                File candidate = new File(storePath, "bin/nix-store");
                if (!candidate.isFile()) continue;
                if (result != null) {
                    throw new IllegalStateException("Bootstrap contains multiple nix-store executables");
                }
                result = candidate;
            }
        }
        if (result == null) {
            throw new IllegalStateException("Bootstrap does not contain nix-store");
        }
        return result;
    }

    private static File loadBundledBootstrap(
        Activity activity,
        String assetPath,
        String expectedSha256
    ) throws Exception {
        PlatformDiagnostics.record(
            activity,
            "BOOTSTRAP",
            "Reading embedded bootstrap asset " + assetPath
        );
        return stageBootstrap(
            activity,
            activity.getAssets().open(assetPath),
            expectedSha256,
            BuildConfig.BOOTSTRAP_ARCHIVE_MAX_BYTES
        );
    }

    private static File stageBootstrap(
        Activity activity,
        InputStream source,
        String expectedSha256,
        long maximumBytes
    ) throws Exception {
        File archive = File.createTempFile("nix-bootstrap-", ".zip", activity.getCacheDir());
        long total;
        try {
            total = copyVerifiedBootstrap(source, archive, expectedSha256, maximumBytes);
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            throw error;
        }
        PlatformDiagnostics.record(
            activity,
            "BOOTSTRAP",
            "Bootstrap verified: " + total + " bytes, SHA-256 " + expectedSha256
        );
        return archive;
    }

    static long copyVerifiedBootstrap(
        InputStream source,
        File archive,
        String expectedSha256,
        long maximumBytes
    ) throws Exception {
        if (maximumBytes <= 0) throw new IllegalArgumentException("Bootstrap size limit must be positive");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (
            InputStream input = new DigestInputStream(source, digest);
            FileOutputStream output = new FileOutputStream(archive)
        ) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) {
                    throw new SecurityException("Bootstrap archive exceeds configured size limit");
                }
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        } catch (Exception error) {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            throw error;
        }
        String actualSha256 = toHex(digest.digest());
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actualSha256)) {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            throw new SecurityException("Bootstrap checksum verification failed");
        }
        return total;
    }

    static File safeStagingPath(String relativePath) throws Exception {
        if (relativePath == null || relativePath.isEmpty() || relativePath.startsWith("/")) {
            throw new SecurityException("Invalid bootstrap archive path");
        }
        File target = new File(TERMUX_STAGING_PREFIX_DIR, relativePath);
        String root = TERMUX_STAGING_PREFIX_DIR.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(root)) {
            throw new SecurityException("Bootstrap archive path escapes the staging directory");
        }
        return target;
    }

    private static String toHex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) text.append(String.format("%02x", item & 0xff));
        return text.toString();
    }

}
