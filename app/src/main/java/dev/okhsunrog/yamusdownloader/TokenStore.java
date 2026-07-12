package dev.okhsunrog.yamusdownloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TokenStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "yandex-music-oauth-token";
    private static final String PREFS = "credentials";
    private static final String CIPHERTEXT = "token_ciphertext";
    private static final String IV = "token_iv";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SharedPreferences preferences;

    TokenStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String load() {
        String ciphertext = preferences.getString(CIPHERTEXT, null);
        String iv = preferences.getString(IV, null);
        if (ciphertext == null || iv == null) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            );
            byte[] plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | RuntimeException error) {
            clear();
            return "";
        }
    }

    void save(String token) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            preferences.edit()
                    .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (GeneralSecurityException | IOException error) {
            throw new IllegalStateException("Не удалось сохранить token в Android Keystore", error);
        }
    }

    void clear() {
        preferences.edit().remove(CIPHERTEXT).remove(IV).apply();
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(ALIAS, null);
        if (existing != null) {
            return existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
