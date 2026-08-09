package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.termux.BuildConfig;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class PlatformKeystore {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String PREFERENCE_KEY = "platform_master_key_v1";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PlatformKeystore() {}

    static synchronized String getOrCreateMasterKey(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(
            BuildConfig.APPLICATION_ID + ".secure",
            Context.MODE_PRIVATE
        );
        String stored = preferences.getString(PREFERENCE_KEY, null);
        SecretKey wrappingKey = loadOrCreateWrappingKey(stored == null);
        if (stored != null) return decrypt(wrappingKey, stored);

        byte[] masterKey = new byte[32];
        RANDOM.nextBytes(masterKey);
        String encoded = Base64.encodeToString(masterKey, Base64.NO_WRAP);
        String encrypted = encrypt(wrappingKey, encoded);
        if (!preferences.edit().putString(PREFERENCE_KEY, encrypted).commit()) {
            throw new IllegalStateException("Unable to persist the wrapped Nix Ship master key");
        }
        return encoded;
    }

    private static SecretKey loadOrCreateWrappingKey(boolean mayCreate) throws Exception {
        String alias = BuildConfig.APPLICATION_ID + ".master-key-wrapping";
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(alias, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        if (!mayCreate) {
            throw new IllegalStateException(
                "The Android Keystore key is unavailable; refusing to replace encrypted state"
            );
        }
        KeyGenerator generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        );
        generator.init(new KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }

    private static String encrypt(SecretKey key, String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return "v1."
            + Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + "."
            + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    private static String decrypt(SecretKey key, String encoded) throws Exception {
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            throw new IllegalStateException("Unsupported wrapped master-key format");
        }
        byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
        if (iv.length != 12) throw new IllegalStateException("Invalid wrapped master-key IV");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] plaintext = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP));
        String result = new String(plaintext, StandardCharsets.UTF_8);
        byte[] decoded = Base64.decode(result, Base64.NO_WRAP);
        if (decoded.length != 32) throw new IllegalStateException("Invalid Nix Ship master key");
        return result;
    }
}
