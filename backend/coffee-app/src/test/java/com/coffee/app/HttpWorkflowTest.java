package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.coffee.payments.api.CheckMac;
import com.coffee.payments.api.TradeQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Actual HTTP/cookies/CSRF plus production-style empty-database bootstrap. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:http-workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "server.servlet.session.cookie.secure=false",
      "app.seed-demo=false",
      "ecpay.enabled=true",
      "ecpay.merchant-id=3002607",
      "ecpay.hash-key=test-key",
      "ecpay.hash-iv=test-iv",
      "app.public-url=https://coffee.example.test",
      "ecpay.reconcile.enabled=true",
      "ecpay.reconcile.interval-ms=86400000",
      "app.bootstrap-username=owner@coffee.local",
      "app.bootstrap-password=BootstrapTest!2026"
    })
class HttpWorkflowTest {
  @MockitoBean TradeQuery tradeQuery;
  @LocalServerPort int port;
  final ObjectMapper json = new ObjectMapper();

  class BrowserSession {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;
    String header;

    HttpResponse<String> request(String method, String path, String body, Map<String, String> extra)
        throws Exception {
      var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
      if (body != null) b.header("Content-Type", "application/json");
      if (!method.equals("GET") && token != null) b.header(header, token);
      extra.forEach(b::header);
      return client.send(
          b.method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode call(String method, String path, String body) throws Exception {
      var r = request(method, path, body, Map.of());
      assertThat(r.statusCode())
          .withFailMessage("%s %s: %s", method, path, r.body())
          .isEqualTo(200);
      return r.body().isBlank() ? json.nullNode() : json.readTree(r.body());
    }

    void login(String name, String password) throws Exception {
      var csrf = call("GET", "/api/auth/csrf", null);
      token = csrf.get("token").asText();
      header = csrf.get("headerName").asText();
      call(
          "POST",
          "/api/auth/login",
          json.writeValueAsString(Map.of("username", name, "password", password)));
      // Successful sign-in rotates CSRF; mirror the Vue client's refresh.
      csrf = call("GET", "/api/auth/csrf", null);
      token = csrf.get("token").asText();
      header = csrf.get("headerName").asText();
    }
  }

  @Test
  void emptyDatabaseToPaidOrderAndReportThroughRealHttp() throws Exception {
    var hq = new BrowserSession();
    hq.login("owner@coffee.local", "BootstrapTest!2026");
    assertThat(hq.call("GET", "/api/branches", null).size()).isZero();
    String branch =
        hq.call(
                "POST",
                "/api/branches",
                "{\"id\":null,\"name\":\"HTTP"
                    + " 測試門市\",\"address\":\"台北市\",\"phone\":\"02-12345678\",\"active\":true,\"monthlyTarget\":10000}")
            .get("id")
            .asText();
    String product =
        hq.call(
                "POST",
                "/api/menu",
                "{\"id\":null,\"name\":\"測試拿鐵\",\"subtitle\":\"現做咖啡\",\"category\":\"經典咖啡\",\"price\":145,\"cost\":40,\"image\":\"latte\",\"badge\":\"\",\"active\":true}")
            .get("id")
            .asText();
    hq.call(
        "POST",
        "/api/menu/" + product + "/options",
        "{\"groupIds\":[\"temperature\",\"sugar\"]}");
    for (String role : new String[] {"CUSTOMER", "CASHIER"})
      hq.call(
          "POST",
          "/api/admin/accounts",
          json.writeValueAsString(
              Map.of(
                  "username",
                  role.toLowerCase() + "@http.local",
                  "name",
                  role,
                  "role",
                  role,
                  "branchId",
                  branch,
                  "active",
                  true,
                  "password",
                  "WorkflowTest!2026")));
    var customer = new BrowserSession();
    customer.login("customer@http.local", "WorkflowTest!2026");
    String body =
        "{\"branchId\":\""
            + branch
            + "\",\"fulfillment\":\"TAKEAWAY\",\"paymentMethod\":\"CASH\",\"note\":\"\",\"items\":[{\"productId\":\""
            + product
            + "\",\"quantity\":2,\"optionIds\":[\"temp-hot\",\"sugar-none\"]}]}";
    var created =
        customer.request(
            "POST", "/api/orders", body, Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertThat(created.statusCode()).isEqualTo(200);
    String id = json.readTree(created.body()).get("id").asText();
    var cashier = new BrowserSession();
    cashier.login("cashier@http.local", "WorkflowTest!2026");
    var paid = cashier.call("POST", "/api/orders/" + id + "/cash", "{\"tendered\":500}");
    assertThat(paid.get("total").asInt()).isEqualTo(290);
    assertThat(paid.get("changeAmount").asInt()).isEqualTo(210);
    var report =
        hq.call("GET", "/api/reports?month=" + YearMonth.now(ZoneId.of("Asia/Taipei")), null);
    assertThat(report.get("revenue").asInt()).isEqualTo(290);
    assertThat(report.get("orders").asInt()).isEqualTo(1);
    assertThat(report.get("grossProfit").asInt()).isEqualTo(210);
    assertThat(customer.request("GET", "/api/admin/accounts", null, Map.of()).statusCode())
        .isEqualTo(403);
    var html = hq.request("GET", "/reports", null, Map.of());
    assertThat(html.statusCode()).isEqualTo(200);
    assertThat(html.body()).contains("<div id=\"app\"></div>");
    assertThat(html.headers().firstValue("Content-Security-Policy").orElse(""))
        .contains("default-src 'self'");
    var asset =
        java.util.regex.Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(html.body());
    assertThat(asset.find()).isTrue();
    assertThat(hq.request("GET", asset.group(1), null, Map.of()).statusCode()).isEqualTo(200);

    // The reconciliation endpoint uses the same real Cookie + CSRF workflow as the UI.
    var online =
        customer.request(
            "POST",
            "/api/orders",
            body.replace("CASH", "ECPAY"),
            Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    assertThat(online.statusCode()).isEqualTo(200);
    String onlineId = json.readTree(online.body()).get("id").asText();
    Map<String, String> fields = new java.util.HashMap<>();
    fields.put("MerchantID", "3002607");
    fields.put("MerchantTradeNo", onlineId);
    fields.put("TradeNo", "T" + onlineId);
    fields.put("TradeAmt", "290");
    fields.put("TradeStatus", "1");
    fields.put("CheckMacValue", CheckMac.sign(fields, "test-key", "test-iv"));
    when(tradeQuery.query(anyString())).thenReturn(fields);
    assertThat(
            customer
                .request("POST", "/api/payments/reconciliation/" + onlineId, null, Map.of())
                .statusCode())
        .isEqualTo(403);
    assertThat(
            hq.call("POST", "/api/payments/reconciliation/" + onlineId, null)
                .get("outcome")
                .asText())
        .isEqualTo("CONFIRMED");
    assertThat(
            hq.call("GET", "/api/payments/reconciliation/" + onlineId, null).get("attempts").size())
        .isEqualTo(1);
    assertThat(
            hq.call("GET", "/api/reports?month=" + YearMonth.now(ZoneId.of("Asia/Taipei")), null)
                .get("revenue")
                .asInt())
        .isEqualTo(580);
  }
}
