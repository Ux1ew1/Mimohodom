package app.fastnotes;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Шифрование обмена с компьютером (docs/SYNC.md): AES-256-GCM, ключ из секрета через HKDF-SHA256. */
public final class SyncCrypto {
  private static final byte[] INFO = "mimohodom-sync-v1".getBytes(StandardCharsets.UTF_8);
  private static final SecureRandom RANDOM = new SecureRandom();

  private SyncCrypto() {}

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** HKDF-SHA256 без соли (как 32 нулевых байта по RFC 5869), один блок вывода. */
  public static byte[] deriveKey(byte[] secret) {
    byte[] prk = hmac(new byte[32], secret);
    byte[] block = new byte[INFO.length + 1];
    System.arraycopy(INFO, 0, block, 0, INFO.length);
    block[INFO.length] = 1;
    return hmac(prk, block);
  }

  public static byte[] requestAad(String device) {
    return ("req|" + device).getBytes(StandardCharsets.UTF_8);
  }

  public static byte[] responseAad(String device) {
    return ("res|" + device).getBytes(StandardCharsets.UTF_8);
  }

  /** {@code nonce(12) || шифртекст || тег(16)}. */
  public static byte[] seal(byte[] key, byte[] aad, byte[] plain) {
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(aad);
      byte[] ct = cipher.doFinal(plain);
      byte[] out = new byte[12 + ct.length];
      System.arraycopy(nonce, 0, out, 0, 12);
      System.arraycopy(ct, 0, out, 12, ct.length);
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Расшифровывает или возвращает null, если данные повреждены или ключ не тот. */
  public static byte[] open(byte[] key, byte[] aad, byte[] data) {
    if (data == null || data.length < 12 + 16) return null;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(128, data, 0, 12));
      cipher.updateAAD(aad);
      return cipher.doFinal(data, 12, data.length - 12);
    } catch (GeneralSecurityException e) {
      return null;
    }
  }

  public static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
    return out.toString();
  }

  public static String randomHex(int bytes) {
    byte[] buffer = new byte[bytes];
    RANDOM.nextBytes(buffer);
    return hex(buffer);
  }
}
