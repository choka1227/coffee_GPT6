package com.coffee.payments.internal;

import com.coffee.orders.api.Orders.InvoiceCandidate;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class EcpayInvoiceClient {
  public static final String STAGE_BASE_URL = "https://einvoice-stage.ecpay.com.tw";
  public sealed interface CallResult permits Ok, Rejected, Unknown {}
  public record Ok(Map<String, Object> data) implements CallResult {}
  public record Rejected(int rtnCode, String rtnMsg) implements CallResult {}
  public record Unknown(String reason) implements CallResult {}

  private final boolean enabled;
  private final String merchant, key, iv;
  private final URI endpoint;
  private final Duration timeout;
  private final HttpClient http;
  private final ObjectMapper json;

  public EcpayInvoiceClient(
      ObjectMapper mapper,
      @Value("${ecpay.invoice.enabled:false}") boolean enabled,
      @Value("${ecpay.invoice.environment:stage}") String environment,
      @Value("${ecpay.invoice.merchant-id:}") String merchant,
      @Value("${ecpay.invoice.hash-key:}") String key,
      @Value("${ecpay.invoice.hash-iv:}") String iv,
      @Value("${ecpay.invoice.base-url:" + STAGE_BASE_URL + "}") String baseUrl,
      @Value("${orders.invoice-required:false}") boolean invoiceRequired,
      @Value("${ecpay.invoice.timeout-ms:10000}") long timeoutMs,
      @Value("${ecpay.invoice.interval-ms:300000}") long intervalMs,
      @Value("${ecpay.invoice.batch-size:20}") int batchSize,
      @Value("${ecpay.invoice.lag-seconds:120}") int lagSeconds,
      @Value("${ecpay.invoice.max-attempts:10}") int maxAttempts,
      @Value("${ecpay.invoice.claim-lease-ms:900000}") long claimLeaseMs) {
    this.enabled = enabled;
    this.merchant = merchant;
    this.key = key;
    this.iv = iv;
    this.json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    if (!enabled) {
      endpoint = null;
      timeout = null;
      http = null;
      return;
    }
    require("stage".equals(environment), "電子發票只允許測試環境");
    require(merchant != null && !merchant.isBlank(), "電子發票特店設定不正確");
    require(validAes(key) && validAes(iv), "電子發票金鑰設定不正確");
    endpoint = validateEndpoint(baseUrl);
    require(invoiceRequired, "啟用電子發票時必須收集發票資訊");
    require(timeoutMs >= 1000 && timeoutMs <= 30000, "電子發票逾時設定不正確");
    require(intervalMs >= 10000, "電子發票排程間隔不正確");
    require(batchSize >= 1 && batchSize <= 100, "電子發票批次筆數不正確");
    require(lagSeconds >= 30 && lagSeconds <= 3600, "電子發票延遲設定不正確");
    require(maxAttempts >= 1 && maxAttempts <= 100, "電子發票重試次數不正確");
    require(claimLeaseMs >= 3 * timeoutMs + 60000 && claimLeaseMs <= 3600000,
        "電子發票認領期限不正確");
    timeout = Duration.ofMillis(timeoutMs);
    http = HttpClient.newBuilder().connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  private static boolean validAes(String value) {
    return value != null && !value.isBlank() && value.length() == 16
        && value.getBytes(StandardCharsets.UTF_8).length == 16;
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new IllegalStateException(message);
  }

  private static URI validateEndpoint(String value) {
    try {
      URI uri = URI.create(value);
      boolean local = "http".equals(uri.getScheme())
          && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
          && uri.getPort() >= 1 && uri.getPort() <= 65535
          && uri.getUserInfo() == null && uri.getRawPath().isEmpty()
          && uri.getQuery() == null && uri.getFragment() == null;
      if (!STAGE_BASE_URL.equals(value) && !local) throw new IllegalArgumentException();
      return uri;
    } catch (RuntimeException e) {
      throw new IllegalStateException("電子發票網址只允許測試環境或本機測試伺服器");
    }
  }

  public CallResult issue(InvoiceCandidate candidate, long timestampSec) {
    if (!enabled) return new Unknown("DISABLED");
    var invoice = candidate.invoice();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("MerchantID", merchant);
    data.put("RelateNumber", candidate.orderId());
    data.put("CustomerEmail", invoice.email());
    data.put("Print", "0");
    data.put("Donation", "DONATE".equals(invoice.type()) ? "1" : "0");
    data.put("LoveCode", "DONATE".equals(invoice.type()) ? invoice.loveCode() : "");
    data.put("CarrierType", switch (invoice.type()) {
      case "CLOUD" -> "1";
      case "MOBILE" -> "3";
      default -> "";
    });
    data.put("CarrierNum", "MOBILE".equals(invoice.type()) ? invoice.carrier() : "");
    data.put("TaxType", "1");
    data.put("SalesAmount", candidate.total());
    data.put("InvType", "07");
    data.put("vat", "1");
    data.put("Items", List.of(Map.of("ItemSeq", 1, "ItemName", "餐飲費用", "ItemCount", 1,
        "ItemWord", "式", "ItemPrice", candidate.total(), "ItemTaxType", "1",
        "ItemAmount", candidate.total())));
    return call("Issue", data, timestampSec);
  }

  public CallResult getIssueByRelateNumber(String orderId) {
    if (!enabled) return new Unknown("DISABLED");
    return call("GetIssue", Map.of("MerchantID", merchant, "RelateNumber", orderId),
        System.currentTimeMillis() / 1000);
  }

  private CallResult call(String path, Map<String, Object> data, long timestampSec) {
    try {
      String body = json.writeValueAsString(Map.of("MerchantID", merchant,
          "RqHeader", Map.of("Timestamp", timestampSec, "Revision", "3.0.0"),
          "Data", EinvoiceCodec.encrypt(json.writeValueAsString(data), key, iv)));
      var response = http.send(HttpRequest.newBuilder(endpoint.resolve("/B2CInvoice/" + path))
          .timeout(timeout).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) return new Unknown("HTTP_" + response.statusCode());
      if (response.body().length() > 65536) return new Unknown("JSON");
      JsonNode outer = json.readTree(response.body());
      if (outer == null || !outer.isObject() || !integer(outer.get("TransCode")))
        return new Unknown("JSON");
      if (outer.get("TransCode").intValue() != 1) return new Unknown("TRANS_CODE");
      if (!outer.path("Data").isTextual()) return new Unknown("JSON");
      JsonNode result = json.readTree(EinvoiceCodec.decrypt(outer.get("Data").textValue(), key, iv));
      if (result == null || !result.isObject() || !integer(result.get("RtnCode")))
        return new Unknown("JSON");
      int code = result.get("RtnCode").intValue();
      if (code == 1) return new Ok(json.convertValue(result, new TypeReference<>() {}));
      String message = result.path("RtnMsg").asText("").replaceAll("[\\r\\n\\t]", " ");
      // Provider messages may echo request fields: never retain known sensitive values.
      for (Object value : List.of(key, iv, merchant)) message = message.replace(value.toString(), "[已隱藏]");
      for (String field : List.of("CustomerEmail", "CarrierNum", "LoveCode")) {
        Object value = data.get(field);
        if (value instanceof String text && !text.isEmpty()) message = message.replace(text, "[已隱藏]");
      }
      return new Rejected(code, message.substring(0, Math.min(100, message.length())));
    } catch (HttpTimeoutException e) {
      return new Unknown("TIMEOUT");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Unknown("INTERRUPTED");
    } catch (EinvoiceException e) {
      return new Unknown("DECRYPT");
    } catch (java.io.IOException e) {
      return new Unknown(e instanceof com.fasterxml.jackson.core.JacksonException ? "JSON" : "CONNECT");
    } catch (RuntimeException e) {
      return new Unknown("JSON");
    }
  }

  private static boolean integer(JsonNode node) {
    return node != null && node.isIntegralNumber() && node.canConvertToInt();
  }
}
