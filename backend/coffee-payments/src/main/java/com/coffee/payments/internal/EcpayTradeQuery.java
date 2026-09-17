package com.coffee.payments.internal;

import com.coffee.payments.api.CheckMac;
import com.coffee.payments.api.TradeQuery;
import com.coffee.shared.Problem;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EcpayTradeQuery implements TradeQuery {
  private final String merchant, key, iv;
  private final URI endpoint;
  private final Duration timeout;
  private final HttpClient client;

  public EcpayTradeQuery(
      @Value("${ecpay.merchant-id:}") String merchant,
      @Value("${ecpay.hash-key:}") String key,
      @Value("${ecpay.hash-iv:}") String iv,
      @Value("${ecpay.environment:stage}") String environment,
      @Value("${ecpay.query-timeout-ms:10000}") long timeoutMs) {
    Problem.check(timeoutMs > 0 && timeoutMs <= 60000, "查單逾時設定需為 1–60000 毫秒");
    this.merchant = merchant;
    this.key = key;
    this.iv = iv;
    this.timeout = Duration.ofMillis(timeoutMs);
    endpoint =
        URI.create(
            "https://"
                + ("production".equals(environment)
                    ? "payment.ecpay.com.tw"
                    : "payment-stage.ecpay.com.tw")
                + "/Cashier/QueryTradeInfo/V5");
    client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public Map<String, String> query(String orderId) {
    Map<String, String> fields = new TreeMap<>();
    fields.put("MerchantID", merchant);
    fields.put("MerchantTradeNo", orderId);
    fields.put("TimeStamp", Long.toString(System.currentTimeMillis() / 1000));
    fields.put("PlatformID", "");
    fields.put("CheckMacValue", CheckMac.sign(fields, key, iv));
    String body =
        fields.entrySet().stream()
            .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
            .collect(Collectors.joining("&"));
    try {
      var response =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .timeout(timeout)
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 || response.body().length() > 65536)
        throw new Problem(503, "綠界查單暫時失敗");
      return parse(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Problem(503, "綠界查單已中斷");
    } catch (java.io.IOException e) {
      throw new Problem(503, "綠界查單暫時失敗");
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public static Map<String, String> parse(String body) {
    Map<String, String> fields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    try {
      for (String pair : body.split("&", -1)) {
        String[] part = pair.split("=", 2);
        if (part.length != 2) throw new IllegalArgumentException();
        String name = URLDecoder.decode(part[0], StandardCharsets.UTF_8);
        String value = URLDecoder.decode(part[1], StandardCharsets.UTF_8);
        if (name.isBlank() || fields.putIfAbsent(name, value) != null)
          throw new IllegalArgumentException();
      }
      return fields;
    } catch (IllegalArgumentException e) {
      throw new Problem(503, "綠界查單回應格式錯誤");
    }
  }
}
