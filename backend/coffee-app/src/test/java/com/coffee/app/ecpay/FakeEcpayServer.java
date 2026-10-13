package com.coffee.app.ecpay;

import com.coffee.payments.api.CheckMac;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeEcpayServer implements AutoCloseable {
  enum Scenario {
    PAID,
    SIMULATED,
    NOT_FOUND,
    AMOUNT_MISMATCH,
    BAD_MAC,
    TIMEOUT,
    HTTP_500,
    REDIRECT_TO_PRODUCTION
  }

  private final String merchantId;
  private final String hashKey;
  private final String hashIv;
  private final HttpServer server;
  private final ExecutorService executor;
  private final List<Map<String, String>> received = new ArrayList<>();
  private volatile Scenario scenario = Scenario.NOT_FOUND;

  static FakeEcpayServer start(String merchantId, String hashKey, String hashIv) {
    try {
      return new FakeEcpayServer(merchantId, hashKey, hashIv);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private FakeEcpayServer(String merchantId, String hashKey, String hashIv) throws IOException {
    this.merchantId = merchantId;
    this.hashKey = hashKey;
    this.hashIv = hashIv;
    var sequence = new AtomicInteger();
    executor =
        Executors.newCachedThreadPool(
            task -> {
              var thread = new Thread(task, "fake-ecpay-" + sequence.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/Cashier/QueryTradeInfo/V5", this::handle);
    server.start();
  }

  String queryUrl() {
    return "http://127.0.0.1:"
        + server.getAddress().getPort()
        + "/Cashier/QueryTradeInfo/V5";
  }

  void respond(Scenario scenario) {
    this.scenario = scenario;
  }

  synchronized List<Map<String, String>> receivedQueries() {
    return List.copyOf(received);
  }

  Map<String, String> signedCallback(
      Map<String, String> checkoutFields, Map<String, String> overrides) {
    var result = new HashMap<String, String>();
    result.put("MerchantID", checkoutFields.get("MerchantID"));
    result.put("MerchantTradeNo", checkoutFields.get("MerchantTradeNo"));
    result.put("TradeNo", tradeNo(checkoutFields.get("MerchantTradeNo")));
    result.put("TradeAmt", checkoutFields.get("TotalAmount"));
    result.put("RtnCode", "1");
    result.put("SimulatePaid", "0");
    result.put(
        "PaymentDate",
        LocalDateTime.now(ZoneId.of("Asia/Taipei"))
            .minusMinutes(1)
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")));
    result.putAll(overrides);
    result.put("CheckMacValue", CheckMac.sign(result, hashKey, hashIv));
    return result;
  }

  private void handle(HttpExchange exchange) throws IOException {
    Map<String, String> request;
    try {
      request = parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      synchronized (this) {
        received.add(Map.copyOf(request));
      }
      long timestamp = Long.parseLong(request.getOrDefault("TimeStamp", "0"));
      boolean valid =
          "POST".equals(exchange.getRequestMethod())
              && merchantId.equals(request.get("MerchantID"))
              && request.getOrDefault("MerchantTradeNo", "").matches("[A-Za-z0-9]{20}")
              && Math.abs(System.currentTimeMillis() / 1000 - timestamp) <= 180
              && CheckMac.valid(request, hashKey, hashIv);
      if (!valid) {
        send(exchange, 400, "invalid request");
        return;
      }
    } catch (RuntimeException e) {
      send(exchange, 400, "invalid request");
      return;
    }

    if (scenario == Scenario.TIMEOUT) {
      try {
        Thread.sleep(1500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        exchange.close();
        return;
      }
    }
    if (scenario == Scenario.HTTP_500) {
      send(exchange, 500, "temporary failure");
      return;
    }
    if (scenario == Scenario.REDIRECT_TO_PRODUCTION) {
      exchange
          .getResponseHeaders()
          .add("Location", "https://payment.ecpay.com.tw/Cashier/QueryTradeInfo/V5");
      exchange.sendResponseHeaders(302, -1);
      exchange.close();
      return;
    }

    String orderId = request.get("MerchantTradeNo");
    var response = new LinkedHashMap<String, String>();
    response.put("MerchantID", merchantId);
    response.put("MerchantTradeNo", orderId);
    if (scenario == Scenario.NOT_FOUND) {
      response.put("TradeStatus", "10200047");
      response.put("TradeAmt", "");
      response.put("TradeNo", "");
    } else {
      response.put("TradeStatus", "1");
      response.put("TradeAmt", scenario == Scenario.AMOUNT_MISMATCH ? "281" : "280");
      response.put("TradeNo", tradeNo(orderId));
      response.put("SimulatePaid", scenario == Scenario.SIMULATED ? "1" : "0");
      response.put(
          "PaymentDate",
          LocalDateTime.now(ZoneId.of("Asia/Taipei"))
              .minusMinutes(1)
              .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")));
    }
    response.put(
        "CheckMacValue",
        CheckMac.sign(response, scenario == Scenario.BAD_MAC ? "wrong-key" : hashKey, hashIv));
    send(exchange, 200, encode(response));
  }

  private static String tradeNo(String orderId) {
    return "T" + orderId.substring(1);
  }

  private static Map<String, String> parse(String body) {
    var result = new HashMap<String, String>();
    for (String pair : body.split("&", -1)) {
      String[] parts = pair.split("=", 2);
      if (parts.length != 2) throw new IllegalArgumentException();
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
      if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
    }
    return result;
  }

  private static String encode(Map<String, String> fields) {
    var parts = new ArrayList<String>();
    fields.forEach(
        (key, value) ->
            parts.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8)));
    return String.join("&", parts);
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/x-www-form-urlencoded");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  int port() {
    return URI.create(queryUrl()).getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS))
        throw new IllegalStateException("fake ECPay executor did not terminate");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
