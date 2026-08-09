package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

public class PlatformRuntimeTest {

    @Test
    public void bootstrapReadinessRequiresExecutableLogin() throws Exception {
        File missing = new File(
            Files.createTempDirectory("nix-bootstrap-ready").toFile(),
            "login"
        );
        Assert.assertFalse(PlatformRuntime.isBootstrapReady(missing));
        Assert.assertTrue(missing.createNewFile());
        Assert.assertFalse(PlatformRuntime.isBootstrapReady(missing));
        Assert.assertTrue(missing.setExecutable(true, true));
        Assert.assertTrue(PlatformRuntime.isBootstrapReady(missing));
        Assert.assertTrue(missing.delete());
        Assert.assertTrue(missing.getParentFile().delete());
    }

    @Test
    public void shellQuoteTreatsMetacharactersAsLiteralText() {
        Assert.assertEquals(
            "'path with '\"'\"' quote and $(command)'",
            PlatformRuntime.shellQuote("path with ' quote and $(command)")
        );
    }

    @Test
    public void formatsAndroidWifiAddressWithoutNativeNodeDiscovery() {
        Assert.assertEquals("192.168.20.41", PlatformRuntime.formatWifiIpv4(0x2914a8c0));
        Assert.assertNull(PlatformRuntime.formatWifiIpv4(0));
        Assert.assertNull(PlatformRuntime.formatWifiIpv4(0x0100007f));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shellQuoteRejectsLineBreaks() {
        PlatformRuntime.shellQuote("first\nsecond");
    }

    @Test
    public void launcherImportsPreparedClosureAndExecutesPinnedStorePath() {
        String storePath = "/nix/store/0123456789abcdfghijklmnpqrsvwxyz-platform";
        PlatformAssets.RuntimeBundle bundle = new PlatformAssets.RuntimeBundle(
            "{}",
            "x86_64-linux",
            storePath,
            "runtime.nar.bundle",
            "0000000000000000000000000000000000000000000000000000000000000000",
            123
        );

        String launcher = PlatformRuntime.buildLauncherScript(
            bundle,
            new File("/private/logs"),
            new File("/private/import.pipe"),
            new File("/private/import.verified")
        );

        Assert.assertTrue(launcher.contains("nix-store --import < '/private/import.pipe'"));
        Assert.assertTrue(launcher.contains("program=\"$runtime/bin/nixship\""));
        Assert.assertTrue(launcher.contains("program=\"$runtime/bin/nixship\""));
        Assert.assertTrue(launcher.contains("exec \"$program\""));
        Assert.assertTrue(launcher.contains("unset BASH_ENV"));
        Assert.assertFalse(launcher.contains("nix run"));
        Assert.assertFalse(launcher.contains("github.com"));
    }

    @Test
    public void bundledRuntimeEnablesTheConfiguredFlakeFeatures() {
        Assert.assertEquals(
            "experimental-features = nix-command flakes",
            com.termux.BuildConfig.NIX_CONFIG
        );
    }
}
