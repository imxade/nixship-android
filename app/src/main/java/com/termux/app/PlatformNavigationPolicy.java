package com.termux.app;

import androidx.annotation.Nullable;

import java.net.URI;
import java.util.Locale;

final class PlatformNavigationPolicy {

    private PlatformNavigationPolicy() {}

    /** Exact HTTPS host allowed for the GitHub App Manifest flow. */
    private static final String GITHUB_HOST = "github.com";

    static boolean isAllowed(
        @Nullable String value,
        String loopbackHost,
        int loopbackPort,
        String quickTunnelHostSuffix
    ) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null) {
                return false;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme)
                && loopbackHost.equals(host)
                && uri.getPort() == loopbackPort) {
                return true;
            }
            if (!"https".equals(scheme) || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return false;
            }
            if (GITHUB_HOST.equals(host)) {
                return true;
            }
            return host.endsWith(quickTunnelHostSuffix)
                && host.length() > quickTunnelHostSuffix.length();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
