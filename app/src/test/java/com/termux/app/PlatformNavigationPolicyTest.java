package com.termux.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlatformNavigationPolicyTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final int PORT = 3001;
    private static final String QUICK_TUNNEL_SUFFIX = ".trycloudflare.com";

    @Test
    public void permitsOnlyConfiguredLoopbackOrigin() {
        assertTrue(allowed("http://127.0.0.1:3001/"));
        assertTrue(allowed("http://127.0.0.1:3001/api/setup/claim?token=value"));
        assertFalse(allowed("http://127.0.0.1/"));
        assertFalse(allowed("http://127.0.0.1:3002/"));
        assertFalse(allowed("https://127.0.0.1:3001/"));
        assertFalse(allowed("http://localhost:3001/"));
        assertFalse(allowed("http://attacker@127.0.0.1:3001/"));
    }

    @Test
    public void permitsOnlyStrictQuickTunnelHttpsHosts() {
        assertTrue(allowed("https://kitsy-example.trycloudflare.com/"));
        assertTrue(allowed("https://KITSY-EXAMPLE.TRYCLOUDFLARE.COM/path"));
        assertFalse(allowed("http://kitsy-example.trycloudflare.com/"));
        assertFalse(allowed("https://trycloudflare.com/"));
        assertFalse(allowed("https://trycloudflare.com.evil.example/"));
        assertFalse(allowed("https://kitsy-example.trycloudflare.com.evil.example/"));
        assertFalse(allowed("https://attacker@kitsy-example.trycloudflare.com/"));
        assertFalse(allowed("https://kitsy-example.trycloudflare.com:444/"));
    }

    @Test
    public void permitsGitHubHttpsForManifestFlow() {
        assertTrue(allowed("https://github.com/settings/apps/new?state=abc"));
        assertTrue(allowed("https://github.com/login"));
        assertTrue(allowed("https://github.com/apps/platform-example/installations/new"));
        assertTrue(allowed("https://GITHUB.COM/settings/apps/new"));
        assertFalse(allowed("http://github.com/settings/apps/new"));
        assertFalse(allowed("https://evil.github.com/settings/apps/new"));
        assertFalse(allowed("https://github.com.evil.example/settings/apps/new"));
        assertFalse(allowed("https://attacker@github.com/settings/apps/new"));
        assertFalse(allowed("https://github.com:8443/settings/apps/new"));
    }

    @Test
    public void rejectsMalformedOrNonNetworkUrls() {
        assertFalse(allowed(null));
        assertFalse(allowed(""));
        assertFalse(allowed("not a URI"));
        assertFalse(allowed("file:///data/local/tmp/value"));
        assertFalse(allowed("javascript:alert(1)"));
        assertFalse(allowed("intent://127.0.0.1:3001/"));
    }

    private static boolean allowed(String value) {
        return PlatformNavigationPolicy.isAllowed(
            value,
            LOOPBACK,
            PORT,
            QUICK_TUNNEL_SUFFIX
        );
    }
}
