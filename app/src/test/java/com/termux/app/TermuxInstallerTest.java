package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

public class TermuxInstallerTest {

    @Test(expected = SecurityException.class)
    public void safeStagingPathRejectsParentTraversal() throws Exception {
        TermuxInstaller.safeStagingPath("../outside");
    }

    @Test(expected = SecurityException.class)
    public void safeStagingPathRejectsAbsolutePaths() throws Exception {
        TermuxInstaller.safeStagingPath("/outside");
    }

    @Test
    public void copiesBootstrapOnlyAfterChecksumVerification() throws Exception {
        byte[] content = "pinned bootstrap".getBytes(StandardCharsets.UTF_8);
        File archive = File.createTempFile("nix-bootstrap-test", ".zip");
        try {
            long copied = TermuxInstaller.copyVerifiedBootstrap(
                new ByteArrayInputStream(content),
                archive,
                sha256(content),
                content.length
            );

            Assert.assertEquals(content.length, copied);
            Assert.assertArrayEquals(content, Files.readAllBytes(archive.toPath()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        }
    }

    @Test(expected = SecurityException.class)
    public void rejectsBootstrapBeyondConfiguredLimit() throws Exception {
        byte[] content = "too large".getBytes(StandardCharsets.UTF_8);
        File archive = File.createTempFile("nix-bootstrap-limit", ".zip");
        try {
            TermuxInstaller.copyVerifiedBootstrap(
                new ByteArrayInputStream(content),
                archive,
                sha256(content),
                content.length - 1
            );
        } finally {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        }
    }

    @Test(expected = SecurityException.class)
    public void rejectsBootstrapWithWrongChecksum() throws Exception {
        byte[] content = "tampered".getBytes(StandardCharsets.UTF_8);
        File archive = File.createTempFile("nix-bootstrap-checksum", ".zip");
        try {
            TermuxInstaller.copyVerifiedBootstrap(
                new ByteArrayInputStream(content),
                archive,
                "0000000000000000000000000000000000000000000000000000000000000000",
                content.length
            );
        } finally {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
        }
    }

    @Test
    public void discoversTheSingleBootstrapNixStoreExecutable() throws Exception {
        File root = Files.createTempDirectory("nix-bootstrap-store").toFile();
        File executable = new File(root, "hash-nix/bin/nix-store");
        Assert.assertTrue(executable.getParentFile().mkdirs());
        Assert.assertTrue(executable.createNewFile());

        Assert.assertEquals(
            executable.getCanonicalFile(),
            TermuxInstaller.findNixStoreBinary(root).getCanonicalFile()
        );

        Assert.assertTrue(executable.delete());
        Assert.assertTrue(executable.getParentFile().delete());
        Assert.assertTrue(executable.getParentFile().getParentFile().delete());
        Assert.assertTrue(root.delete());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsBootstrapWithoutNixStore() throws Exception {
        File root = Files.createTempDirectory("nix-bootstrap-no-store").toFile();
        try {
            TermuxInstaller.findNixStoreBinary(root);
        } finally {
            Assert.assertTrue(root.delete());
        }
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder value = new StringBuilder();
        for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }
}
