package com.coffee.payments.internal;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Public only for the composition-layer fake server; business modules use payments.api. */
public final class EinvoiceCodec {
  private EinvoiceCodec() {}

  public static String phpUrlEncode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("*", "%2A");
    } catch (RuntimeException e) {
      throw new EinvoiceException();
    }
  }

  public static String phpUrlDecode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      throw new EinvoiceException();
    }
  }

  public static String encrypt(String json, String key, String iv) {
    try {
      return Base64.getEncoder().encodeToString(cipher(Cipher.ENCRYPT_MODE, key, iv)
          .doFinal(phpUrlEncode(json).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new EinvoiceException();
    }
  }

  public static String decrypt(String base64, String key, String iv) {
    try {
      byte[] plain = cipher(Cipher.DECRYPT_MODE, key, iv)
          .doFinal(Base64.getDecoder().decode(base64));
      return phpUrlDecode(new String(plain, StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new EinvoiceException();
    }
  }

  private static Cipher cipher(int mode, String key, String iv) throws Exception {
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    byte[] ivBytes = iv.getBytes(StandardCharsets.UTF_8);
    if (keyBytes.length != 16 || ivBytes.length != 16) throw new EinvoiceException();
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(mode, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(ivBytes));
    return cipher;
  }
}

final class EinvoiceException extends RuntimeException {
  EinvoiceException() {
    super("電子發票資料編解碼失敗");
  }
}
