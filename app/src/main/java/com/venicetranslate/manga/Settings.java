package com.venicetranslate.manga;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.LocalDate;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
final class Settings {
    static final String DEFAULT_MODEL = "qwen3-235b-a22b-instruct-2507";
    static final String TOOMICS = "https://toomics.com/en/webtoon/detail/code/219024/ep/32/toon/7230";
    private static final String ALIAS = "venice_manga_key_v1";
    private static final String PREF = "manga_settings_v1";
    private Settings() {}
    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
    static String model(Context c) {
        return prefs(c).getString("model", DEFAULT_MODEL);
    }
    static int interval(Context c) {
        return Math.min(8000, Math.max(1400, prefs(c).getInt("interval", 2400)));
    }
    static int maxDaily(Context c) {
        return Math.min(500, Math.max(1, prefs(c).getInt("max_daily", 75)));
    }
    static synchronized int callsToday(Context c) {
        SharedPreferences p = prefs(c);
        if (!LocalDate.now().toString().equals(p.getString("day", ""))) return 0;
        return p.getInt("daily_calls", 0);
    }
    static synchronized boolean reserveRequest(Context c) {
        if (callsToday(c) >= maxDaily(c)) return false;
        SharedPreferences p = prefs(c);
        p.edit().putString("day", LocalDate.now().toString())
                .putInt("daily_calls", callsToday(c) + 1).apply();
        return true;
    }
    static void rememberError(Context c, String msg) {
        prefs(c).edit().putString("last_error", msg).apply();
    }
    static synchronized void saveKey(Context c, String text) throws Exception {
        if (text.isEmpty()) {
            prefs(c).edit().remove("key_blob").apply();
            return;
        }
        SecretKey key = secretKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] all = Arrays.copyOf(iv, iv.length + body.length);
        System.arraycopy(body, 0, all, iv.length, body.length);
        prefs(c).edit().putString("key_blob", Base64.encodeToString(all, Base64.NO_WRAP)).apply();
    }
    static synchronized String loadKey(Context c) {
        try {
            String encoded = prefs(c).getString("key_blob", "");
            if (encoded.isEmpty()) return "";
            byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
            if (all.length < 29) return "";
            byte[] iv = Arrays.copyOfRange(all, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(all, 12, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
    private static SecretKey secretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
