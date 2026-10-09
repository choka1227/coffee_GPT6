package com.coffee.shared;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 程序內的固定視窗限流。視窗從某個鍵的第一次 hit 開始算，額度用完就擋到視窗結束。
 *
 * <p>所有時間由呼叫端傳入（epoch millis），所以可以純單元測試，不需要睡覺也不需要時鐘替身。
 *
 * <p>狀態在程序內，重啟歸零，也不跨實例共用。
 */
public final class RateLimiter {
  private static final String BUSY_MESSAGE = "系統忙碌，請稍後再試";

  private final String message;
  private final int limit;
  private final long windowMs;
  private final int maxKeys;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  private record Window(int count, long until) {}

  public RateLimiter(String message, int limit, long windowMs, int maxKeys) {
    Problem.check(limit >= 1, "限流次數必須至少為 1");
    Problem.check(windowMs >= 1, "限流視窗必須至少為 1 毫秒");
    Problem.check(maxKeys >= 1, "限流鍵數上限必須至少為 1");
    this.message = message;
    this.limit = limit;
    this.windowMs = windowMs;
    this.maxKeys = maxKeys;
  }

  /** 記一次並在超額時丟 429。同一個鍵的併發呼叫不會同時放行。 */
  public void hit(String key, long nowEpochMs) {
    if (windows.size() > maxKeys) {
      windows.entrySet().removeIf(entry -> entry.getValue().until() <= nowEpochMs);
      if (windows.size() > maxKeys) throw new Problem(429, BUSY_MESSAGE);
    }

    windows.compute(
        key,
        (ignored, window) -> {
          if (window == null || window.until() <= nowEpochMs) {
            return new Window(1, nowEpochMs + windowMs);
          }
          if (window.count() >= limit) throw new Problem(429, message);
          return new Window(window.count() + 1, window.until());
        });
  }

  /** 清掉某個鍵的計數。鍵不存在時什麼都不做。 */
  public void clear(String key) {
    windows.remove(key);
  }

  /** 目前追蹤的鍵數。只給測試用。 */
  public int size() {
    return windows.size();
  }
}
