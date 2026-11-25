package com.asistan.cip;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.net.Uri;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AssistantApiClient {
    private static final String PREFERENCES = "cip_settings";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_API_SECRET = "encrypted_api_key";
    private static final String KEYSTORE_ALIAS = "cip_api_key";

    private AssistantApiClient() {
    }

    static String sendCommand(Context context, String command, String sessionId, String history)
            throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String server = trimTrailingSlash(preferences.getString(KEY_SERVER_URL, ""));
        Uri serverUri = Uri.parse(server);
        if (!"https".equalsIgnoreCase(serverUri.getScheme()) || serverUri.getHost() == null) {
            throw new IllegalStateException("Geçerli bir HTTPS sunucu adresi ayarlanmamış.");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(server + "/komut").openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + readApiKey(preferences));
        connection.setRequestProperty("Accept", "text/plain");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        String body = "text=" + encode(command)
                + "&session=" + encode(sessionId)
                + "&gecmis=" + encode(history);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }

        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(status == 401
                        ? "API anahtarı geçersiz."
                        : "Sunucu isteği tamamlayamadı.");
            }
            InputStream stream = connection.getInputStream();
            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (response.length() + line.length() > 20000) {
                        throw new IllegalStateException("Yanıt izin verilen boyutu aştı.");
                    }
                    response.append(line).append('\n');
                }
            }
            return response.toString().trim();
        } finally {
            connection.disconnect();
        }
    }

    private static String readApiKey(SharedPreferences preferences) throws Exception {
        String stored = preferences.getString(KEY_API_SECRET, "");
        if (stored.isEmpty()) {
            throw new IllegalStateException("API anahtarı ayarlanmamış.");
        }
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalStateException("Saklanan API anahtarı okunamadı.");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.DEFAULT)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)), StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEYSTORE_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
