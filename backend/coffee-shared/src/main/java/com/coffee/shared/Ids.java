package com.coffee.shared;

public final class Ids {
  private Ids() {}

  public static String next() {
    return java.util.UUID.randomUUID().toString();
  }

  public static String order() {
    return "CF"
        + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18).toUpperCase();
  }
}
