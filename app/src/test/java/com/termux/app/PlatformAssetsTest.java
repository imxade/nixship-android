package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

public class PlatformAssetsTest {

    @Test
    public void acceptsOnlyNeutralRuntimeBundleAssetNames() {
        Assert.assertTrue(PlatformAssets.isValidArchiveName("runtime.nar.bundle"));
        Assert.assertFalse(PlatformAssets.isValidArchiveName("runtime.nar.gz"));
        Assert.assertFalse(PlatformAssets.isValidArchiveName("../runtime.nar.bundle"));
    }

    @Test
    public void deleteRecursivelyDoesNotFollowSymbolicLinks() throws Exception {
        Path root = Files.createTempDirectory("platform-assets-test");
        Path outside = Files.createTempDirectory("platform-assets-outside");
        Path sentinel = outside.resolve("sentinel");
        Files.write(sentinel, "keep".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(root.resolve("external"), outside);

        PlatformAssets.deleteRecursively(root.toFile());

        Assert.assertFalse(root.toFile().exists());
        Assert.assertTrue(Files.isRegularFile(sentinel));
        Files.delete(sentinel);
        Files.delete(outside);
    }

    @Test
    public void verifiesAndDecompressesRuntimeArchive() throws Exception {
        byte[] content = "complete exported Nix closure".getBytes(StandardCharsets.UTF_8);
        byte[] archive = gzip(content);
        File destination = File.createTempFile("nix-runtime", ".nar");
        PlatformAssets.RuntimeBundle bundle = bundle(archive, sha256(archive));

        PlatformAssets.streamRuntimeArchive(
            new ByteArrayInputStream(archive),
            bundle,
            destination
        );

        Assert.assertArrayEquals(content, Files.readAllBytes(destination.toPath()));
        Assert.assertTrue(destination.delete());
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsRuntimeArchiveWithWrongChecksum() throws Exception {
        byte[] archive = gzip("runtime".getBytes(StandardCharsets.UTF_8));
        File destination = File.createTempFile("nix-runtime-invalid", ".nar");
        try {
            PlatformAssets.streamRuntimeArchive(
                new ByteArrayInputStream(archive),
                bundle(archive, "0000000000000000000000000000000000000000000000000000000000000000"),
                destination
            );
        } finally {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
        }
    }

    private static PlatformAssets.RuntimeBundle bundle(byte[] archive, String checksum) {
        return new PlatformAssets.RuntimeBundle(
            "{}",
            "x86_64-linux",
            "/nix/store/0123456789abcdfghijklmnpqrsvwxyz-platform",
            "runtime.nar.bundle",
            checksum,
            archive.length
        );
    }

    private static byte[] gzip(byte[] content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(content);
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder value = new StringBuilder();
        for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }
}
