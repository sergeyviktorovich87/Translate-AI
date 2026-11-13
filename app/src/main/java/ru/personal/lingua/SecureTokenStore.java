package ru.personal.lingua;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Token records are AES/GCM encrypted using Android Keystore; app backup is disabled. */
final class SecureTokenStore {
    private static final String ALIAS = "personal_lingua_oauth_aes_v1";
    private final SharedPreferences prefs;
    SecureTokenStore(Context ctx) { prefs = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE); }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
    synchronized void save(JSONObject obj) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] data = cipher.doFinal(obj.toString().getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.getEncoder().encodeToString(cipher.getIV()) + "." +
                Base64.getEncoder().encodeToString(data);
        if (!prefs.edit().putString("encrypted_auth", encoded).commit())
            throw new IllegalStateException("Failed to save tokens");
    }
    synchronized JSONObject load() throws Exception {
        String encoded = prefs.getString("encrypted_auth", "");
        if (encoded.isEmpty()) return null;
        String[] values = encoded.split("\\.", 2);
        if (values.length != 2) throw new IllegalArgumentException("Encrypted token record damaged");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(128, Base64.getDecoder().decode(values[0])));
        byte[] decoded = cipher.doFinal(Base64.getDecoder().decode(values[1]));
        return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
    }
    synchronized void clear() { prefs.edit().remove("encrypted_auth").apply(); }
    synchronized String hostId() {
        String current = prefs.getString("host_id", null);
        if (current != null) return current;
        current = "urn:uuid:" + java.util.UUID.randomUUID();
        prefs.edit().putString("host_id", current).apply();
        return current;
    }
}
