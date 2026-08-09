package com.termux.app;

import android.content.Context;
import android.content.res.AssetManager;

import com.termux.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class PlatformAssets {

    private static final Pattern STORE_PATH = Pattern.compile(
        "^/nix/store/[0-9a-df-np-sv-z]{32}-[A-Za-z0-9+._?=-]+$"
    );
    private static final Pattern ARCHIVE_NAME = Pattern.compile("^[A-Za-z0-9._-]+\\.nar\\.bundle$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private PlatformAssets() {}

    static RuntimeBundle loadRuntimeBundle(Context context) throws IOException {
        String assetRoot = BuildConfig.CONTROL_PLANE_ASSET;
        String manifestPath = assetRoot + "/runtime.json";
        String manifest = readAssetText(context.getAssets(), manifestPath);
        try {
            JSONObject value = new JSONObject(manifest);
            int schemaVersion = value.getInt("schemaVersion");
            String repository = value.getString("repository");
            String branch = value.getString("branch");
            String revision = value.getString("revision");
            String system = value.getString("system");
            String storePath = value.getString("storePath");
            String archive = value.getString("archive");
            String archiveSha256 = value.getString("archiveSha256").toLowerCase(Locale.ROOT);
            long archiveBytes = value.getLong("archiveBytes");

            if (schemaVersion != 1) throw new IOException("Unsupported runtime bundle schema");
            if (!BuildConfig.CONTROL_PLANE_REPOSITORY.equals(repository)
                || !BuildConfig.CONTROL_PLANE_BRANCH.equals(branch)
                || !BuildConfig.CONTROL_PLANE_REVISION.equals(revision)) {
                throw new IOException("Runtime bundle provenance does not match this APK");
            }
            if (!BuildConfig.RUNTIME_SYSTEM.equals(system)) {
                throw new IOException(
                    "Runtime bundle targets " + system + " but this device requires "
                        + BuildConfig.RUNTIME_SYSTEM
                );
            }
            if (!STORE_PATH.matcher(storePath).matches()) {
                throw new IOException("Runtime bundle contains an invalid Nix store path");
            }
            if (!isValidArchiveName(archive)) {
                throw new IOException("Runtime bundle contains an invalid archive name");
            }
            if (!SHA256.matcher(archiveSha256).matches()) {
                throw new IOException("Runtime bundle contains an invalid archive checksum");
            }
            if (archiveBytes <= 0 || archiveBytes > BuildConfig.RUNTIME_ARCHIVE_MAX_BYTES) {
                throw new IOException("Runtime bundle archive size is outside the accepted range");
            }
            String archiveAssetPath = assetRoot + "/" + archive;
            try (InputStream ignored = context.getAssets().open(
                archiveAssetPath,
                AssetManager.ACCESS_STREAMING
            )) {
                // Prove the signed APK contains the declared archive before starting the service.
            }
            return new RuntimeBundle(
                manifest,
                system,
                storePath,
                archiveAssetPath,
                archiveSha256,
                archiveBytes
            );
        } catch (JSONException error) {
            throw new IOException("Runtime bundle manifest is malformed", error);
        }
    }

    static void streamRuntimeArchive(
        Context context,
        RuntimeBundle bundle,
        File destination
    ) throws IOException {
        InputStream asset = context.getAssets().open(
            bundle.archiveAssetPath,
            AssetManager.ACCESS_STREAMING
        );
        streamRuntimeArchive(asset, bundle, destination, context);
    }

    static void streamRuntimeArchive(
        InputStream asset,
        RuntimeBundle bundle,
        File destination
    ) throws IOException {
        streamRuntimeArchive(asset, bundle, destination, null);
    }

    private static void streamRuntimeArchive(
        InputStream asset,
        RuntimeBundle bundle,
        File destination,
        Context context
    ) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
        CountingInputStream counted;
        try (
            InputStream archive = asset;
            CountingInputStream counter = new CountingInputStream(archive);
            DigestInputStream verified = new DigestInputStream(counter, digest);
            GZIPInputStream decompressed = new GZIPInputStream(verified, 64 * 1024);
            FileOutputStream output = new FileOutputStream(destination)
        ) {
            counted = counter;
            byte[] buffer = new byte[64 * 1024];
            long nextProgress = 32L * 1024L * 1024L;
            int count;
            while ((count = decompressed.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                if (context != null && counter.count >= nextProgress) {
                    PlatformDiagnostics.record(
                        context,
                        "IMPORT",
                        "Streamed " + counter.count + "/" + bundle.archiveBytes
                            + " compressed runtime bytes"
                    );
                    nextProgress = counter.count + 32L * 1024L * 1024L;
                }
            }
            output.flush();
        }
        String actualSha256 = toHex(digest.digest());
        if (counted.count != bundle.archiveBytes) {
            throw new IOException(
                "Runtime archive size mismatch: expected " + bundle.archiveBytes
                    + " bytes, read " + counted.count
            );
        }
        if (!actualSha256.equals(bundle.archiveSha256)) {
            throw new IOException("Runtime archive checksum verification failed");
        }
    }

    static boolean isRuntimeInstalled(RuntimeBundle bundle) {
        File executable = new File(bundle.storePath, "bin/nixship");
        return executable.isFile() && executable.canExecute();
    }

    static boolean isValidArchiveName(String archive) {
        return archive != null
            && ARCHIVE_NAME.matcher(archive).matches()
            && !archive.contains("..");
    }

    private static String readAssetText(AssetManager assets, String path) throws IOException {
        try (InputStream input = assets.open(path, AssetManager.ACCESS_BUFFER)) {
            byte[] data = new byte[16 * 1024];
            int offset = 0;
            int count;
            while ((count = input.read(data, offset, data.length - offset)) != -1) {
                offset += count;
                if (offset == data.length) throw new IOException("Runtime manifest is too large");
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte item : value) text.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return text.toString();
    }

    static void deleteRecursively(File target) throws IOException {
        if (!target.exists()) return;
        if (!Files.isSymbolicLink(target.toPath())) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        if (!target.delete()) throw new IOException("Unable to delete " + target);
    }

    static final class RuntimeBundle {
        final String manifest;
        final String system;
        final String storePath;
        final String archiveAssetPath;
        final String archiveSha256;
        final long archiveBytes;

        RuntimeBundle(
            String manifest,
            String system,
            String storePath,
            String archiveAssetPath,
            String archiveSha256,
            long archiveBytes
        ) {
            this.manifest = manifest;
            this.system = system;
            this.storePath = storePath;
            this.archiveAssetPath = archiveAssetPath;
            this.archiveSha256 = archiveSha256;
            this.archiveBytes = archiveBytes;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int result = super.read(buffer, offset, length);
            if (result > 0) count += result;
            return result;
        }
    }
}
