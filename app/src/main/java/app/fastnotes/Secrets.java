package app.fastnotes;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class Secrets {
  private static javax.crypto.SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("polza")) {
      KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  "polza", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (javax.crypto.SecretKey) ks.getKey("polza", null);
  }

  public static void save(Context c, String value) throws Exception {
    if (value.isEmpty()) {
      c.getSharedPreferences("secret", 0).edit().clear().apply();
      return;
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    c.getSharedPreferences("secret", 0)
        .edit()
        .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
        .putString(
            "value",
            Base64.encodeToString(
                cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Base64.NO_WRAP))
        .apply();
  }

  public static String read(Context c) throws Exception {
    var p = c.getSharedPreferences("secret", 0);
    String value = p.getString("value", "");
    if (value.isEmpty()) return "";
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(p.getString("iv", ""), Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)),
        java.nio.charset.StandardCharsets.UTF_8);
  }
}
