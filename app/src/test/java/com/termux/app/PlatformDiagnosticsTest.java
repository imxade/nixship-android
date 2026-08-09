package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class PlatformDiagnosticsTest {

    @Test
    public void redactsCredentialsAndSetupToken() {
        String setupToken = "claim-token-that-must-not-leak";
        String input = "Authorization: Bearer abc123\n"
            + "password=hunter2 token=visible? secret: value\n"
            + "http://localhost/claim?token=" + setupToken + "&next=/";

        String output = PlatformDiagnostics.redact(input, setupToken);

        Assert.assertFalse(output.contains("abc123"));
        Assert.assertFalse(output.contains("hunter2"));
        Assert.assertFalse(output.contains(setupToken));
        Assert.assertTrue(output.contains("<redacted>"));
    }

    @Test
    public void tailIsBoundedAndStartsAtACompleteLine() throws Exception {
        File log = File.createTempFile("platform-diagnostics", ".log");
        Files.write(
            log.toPath(),
            "first line\nsecond line\nthird line\n".getBytes(StandardCharsets.UTF_8)
        );

        String tail = PlatformDiagnostics.readTail(log, 22);

        Assert.assertEquals("third line\n", tail);
        Assert.assertTrue(log.delete());
    }

    @Test
    public void missingLogExplainsThatNoOutputExists() {
        File missing = new File(
            System.getProperty("java.io.tmpdir"),
            "missing-platform-log-" + System.nanoTime()
        );

        Assert.assertEquals("(no output yet)\n", PlatformDiagnostics.readTail(missing, 1024));
    }

    @Test
    public void processExitOutputPrefersStderrAndBoundsItsTail() {
        Assert.assertEquals(
            "…rror detail",
            PlatformDiagnostics.processExitOutput("ignored prefix error detail", "stdout", 12)
        );
        Assert.assertEquals(
            "stdout only",
            PlatformDiagnostics.processExitOutput("", "stdout\nonly", 32)
        );
        Assert.assertNull(PlatformDiagnostics.processExitOutput("", "", 32));
    }

    @Test
    public void processExitDescriptionNamesAbortSignal() {
        Assert.assertEquals("code 134 (signal 6, SIGABRT)", PlatformDiagnostics.describeExit(134));
        Assert.assertEquals("code 1", PlatformDiagnostics.describeExit(1));
        Assert.assertEquals("an unavailable exit code", PlatformDiagnostics.describeExit(null));
    }
}
