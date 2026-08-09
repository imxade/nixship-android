package com.termux.app;

import androidx.annotation.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;

final class PlatformSetupToken {

    private PlatformSetupToken() {}

    @Nullable
    static String read(File tokenFile, int minimumCharacters, int maximumBytes) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                tokenFile.toPath(),
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isRegularFile()
                || attributes.size() < minimumCharacters
                || attributes.size() > maximumBytes) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(tokenFile.toPath());
            if (bytes.length > maximumBytes) return null;
            String token = new String(bytes, StandardCharsets.UTF_8).trim();
            if (token.length() < minimumCharacters
                || token.length() > maximumBytes
                || !token.matches("[A-Za-z0-9_-]+")) {
                return null;
            }
            return token;
        } catch (Exception ignored) {
            return null;
        }
    }
}
