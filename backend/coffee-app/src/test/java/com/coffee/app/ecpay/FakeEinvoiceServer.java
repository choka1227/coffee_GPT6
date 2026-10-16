package com.coffee.app.ecpay;

import com.coffee.payments.internal.EinvoiceCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class FakeEinvoiceServer implements AutoCloseable {
  public enum Scenario {
    OK, DUPLICATE, REJECTED, SUCCEED_THEN_TIMEOUT, TIMEOUT, DELAYED_EFFECT,
    HTTP_500, TRANS_CODE_ERROR, BAD_CIPHER, AMOUNT_MISMATCH, REDIRECT_TO_PRODUCTION,
    BAD_JSON, BAD_DATA_JSON, OTHER_REJECTED, MISSING_FIELDS, STRING_AMOUNT
  }

  public record Request(String path, JsonNode outer, JsonNode data) {
    public long timestampSec() { return outer.path("RqHeader").path("Timestamp").longValue(); }
  }

  private final ObjectMapper json = new ObjectMapper();
  private final String key, iv;
  private final HttpServer server;
  private final ExecutorService executor;
  private final List<Thread> workers = new CopyOnWriteArrayList<>();
  private final List<Request> received = new CopyOnWriteArrayList<>();
  private final Map<String, Map<String, Object>> invoices = new ConcurrentHashMap<>();
  private final List<Request> delayed = new CopyOnWriteArrayList<>();
  private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();
  private final Map<String, Consumer<Request>> hooks = new ConcurrentHashMap<>();
  private final AtomicInteger sequence = new AtomicInteger();

  public FakeEinvoiceServer(String key, String iv) throws IOException {
    this.key = key;
    this.iv = iv;
    executor = Executors.newCachedThreadPool(task -> {
      Thread worker = new Thread(task, "fake-einvoice-" + workers.size());
      worker.setDaemon(true);
      workers.add(worker);
      return worker;
    });
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/B2CInvoice/Issue", this::handle);
    server.createContext("/B2CInvoice/GetIssue", this::handle);
    server.start();
  }

  public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
  public void respond(String operation, Scenario scenario) { scenarios.put(operation, scenario); }
  public void hook(String operation, Consumer<Request> hook) { hooks.put(operation, hook); }
  public List<Request> received() { return List.copyOf(received); }
  public int invoiceCount() { return invoices.size(); }
  public void applyDelayed() { delayed.forEach(this::remember); delayed.clear(); }
  public void dropDelayed() { delayed.clear(); }
  public void reset() {
    received.clear();
    invoices.clear();
    delayed.clear();
    scenarios.clear();
    hooks.clear();
    sequence.set(0);
  }

  private Map<String, Object> remember(Request request) {
    return invoices.computeIfAbsent(request.data().path("RelateNumber").asText(), id -> {
      Map<String, Object> invoice = new LinkedHashMap<>();
      invoice.put("InvoiceNo", "AB" + String.format(Locale.ROOT, "%08d", sequence.incrementAndGet()));
      invoice.put("InvoiceDate", "2026-10-16 12:00:00");
      invoice.put("RandomNumber", "1234");
      invoice.put("SalesAmount", request.data().path("SalesAmount").intValue());
      return invoice;
    });
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      JsonNode outer = json.readTree(exchange.getRequestBody());
      JsonNode data = json.readTree(EinvoiceCodec.decrypt(outer.path("Data").asText(), key, iv));
      Request request = new Request(exchange.getRequestURI().getPath(), outer, data);
      received.add(request);
      String operation = request.path().endsWith("/Issue") ? "Issue" : "GetIssue";
      hooks.getOrDefault(operation, ignored -> {}).accept(request);
      Scenario scenario = scenarios.getOrDefault(operation, Scenario.OK);
      if (scenario == Scenario.REDIRECT_TO_PRODUCTION) {
        exchange.getResponseHeaders().set("Location", "https://einvoice.ecpay.com.tw/B2CInvoice/Issue");
        send(exchange, 302, "");
        return;
      }
      if (scenario == Scenario.HTTP_500) { send(exchange, 500, ""); return; }
      if (scenario == Scenario.BAD_JSON) { send(exchange, 200, "{"); return; }
      boolean issue = operation.equals("Issue");
      if (scenario == Scenario.DELAYED_EFFECT && issue) delayed.add(request);
      if (scenario == Scenario.SUCCEED_THEN_TIMEOUT && issue) remember(request);
      if (scenario == Scenario.TIMEOUT || scenario == Scenario.SUCCEED_THEN_TIMEOUT
          || (scenario == Scenario.DELAYED_EFFECT && issue)) {
        try { Thread.sleep(60000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return;
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("RtnCode", 1);
      if (scenario == Scenario.REJECTED || scenario == Scenario.OTHER_REJECTED) {
        result.put("RtnCode", issue || scenario == Scenario.OTHER_REJECTED ? 4000001 : 1600003);
        result.put("RtnMsg", "參數錯誤");
      } else if (issue) {
        result.putAll(remember(request));
        if (scenario == Scenario.DUPLICATE) result.put("RtnCode", 10000009);
      } else {
        var invoice = invoices.get(data.path("RelateNumber").asText());
        if (invoice == null) result.put("RtnCode", 1600003);
        else {
          result.put("IIS_Number", invoice.get("InvoiceNo"));
          result.put("IIS_Create_Date", invoice.get("InvoiceDate"));
          result.put("IIS_Random_Number", invoice.get("RandomNumber"));
          result.put("IIS_Sales_Amount", (int) invoice.get("SalesAmount")
              + (scenario == Scenario.AMOUNT_MISMATCH ? 1 : 0));
          result.put("IIS_Invalid_Status", "0");
          result.put("IIS_Remain_Allowance_Amt", invoice.get("SalesAmount"));
        }
      }
      if (scenario == Scenario.MISSING_FIELDS) result.remove(issue ? "InvoiceNo" : "IIS_Number");
      if (scenario == Scenario.STRING_AMOUNT && result.containsKey("IIS_Sales_Amount"))
        result.put("IIS_Sales_Amount", "0" + result.get("IIS_Sales_Amount"));
      String encrypted = scenario == Scenario.BAD_CIPHER ? "bad-cipher"
          : EinvoiceCodec.encrypt(scenario == Scenario.BAD_DATA_JSON ? "{" : json.writeValueAsString(result), key, iv);
      send(exchange, 200, json.writeValueAsString(Map.of("TransCode",
          scenario == Scenario.TRANS_CODE_ERROR ? 999 : 1, "Data", encrypted)));
    } catch (RuntimeException ignored) {
      // Never log request/ciphertext, secrets or stack traces from the fake server.
    }
  }

  private void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
      // Executor termination may precede the last worker's actual thread death.
      for (Thread worker : workers) {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("電子發票測試伺服器關閉被中斷");
    }
    if (workers.stream().anyMatch(Thread::isAlive))
      throw new IllegalStateException("電子發票測試伺服器執行緒尚未結束");
  }
}
