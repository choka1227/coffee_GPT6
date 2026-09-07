package com.coffee.payments.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * ECPay All-in-one SHA-256; encode the entire sorted string using .NET-compatible form encoding.
 */
public final class CheckMac {
  private CheckMac() {}

  public static String sign(Map<String, String> params, String key, String iv) {
    var sorted = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    params.forEach(
        (k, v) -> {
          if (!k.equalsIgnoreCase("CheckMacValue")) sorted.put(k, v);
        });
    StringBuilder raw = new StringBuilder("HashKey=").append(key);
    sorted.forEach((k, v) -> raw.append('&').append(k).append('=').append(v));
    raw.append("&HashIV=").append(iv);
    String encoded =
        URLEncoder.encode(raw.toString(), StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT)
            .replace("%21", "!")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%2a", "*")
            .replace("%2d", "-")
            .replace("%2e", ".")
            .replace("%5f", "_");
    try {
      return HexFormat.of()
          .withUpperCase()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encoded.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean valid(Map<String, String> p, String key, String iv) {
    String mac = p.get("CheckMacValue");
    return mac != null
        && MessageDigest.isEqual(
            sign(p, key, iv).getBytes(StandardCharsets.UTF_8),
            mac.getBytes(StandardCharsets.UTF_8));
  }
}
