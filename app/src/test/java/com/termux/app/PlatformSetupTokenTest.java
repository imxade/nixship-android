package com.termux.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class PlatformSetupTokenTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void readsBoundedUrlSafeToken() throws Exception {
        File token = temporaryFolder.newFile("setup-token.txt");
        Files.write(token.toPath(), "valid_token-123456\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("valid_token-123456", PlatformSetupToken.read(token, 16, 64));
    }

    @Test
    public void rejectsMissingMalformedAndOversizedTokens() throws Exception {
        File missing = new File(temporaryFolder.getRoot(), "missing");
        assertNull(PlatformSetupToken.read(missing, 16, 64));

        File malformed = temporaryFolder.newFile("malformed");
        Files.write(malformed.toPath(), "invalid token value".getBytes(StandardCharsets.UTF_8));
        assertNull(PlatformSetupToken.read(malformed, 16, 64));

        File oversized = temporaryFolder.newFile("oversized");
        Files.write(oversized.toPath(), new byte[65]);
        assertNull(PlatformSetupToken.read(oversized, 16, 64));
    }

    @Test
    public void rejectsSymbolicLink() throws Exception {
        File realToken = temporaryFolder.newFile("real-token");
        Files.write(realToken.toPath(), "valid_token-123456".getBytes(StandardCharsets.UTF_8));
        File link = new File(temporaryFolder.getRoot(), "linked-token");
        Files.createSymbolicLink(link.toPath(), realToken.toPath());
        assertNull(PlatformSetupToken.read(link, 16, 64));
    }
}
