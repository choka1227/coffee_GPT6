package com.coffee.app.ecpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.payments.internal.EcpayTradeQuery;
import com.coffee.payments.internal.ReconciliationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:ecpay-expiry-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "server.servlet.session.cookie.secure=false",
      "ecpay.enabled=true",
      "ecpay.environment=stage",
      "ecpay.merchant-id=3002607",
      "ecpay.hash-key=test-key-g04",
      "ecpay.hash-iv=test-iv-g04",
      "ecpay.query-timeout-ms=500",
      "ecpay.reconcile.enabled=true",
      "ecpay.reconcile.interval-ms=86400000",
      "ecpay.reconcile.min-age-minutes=0",
      "ecpay.reconcile.max-age-days=1",
      "ecpay.expire.enabled=true",
      "ecpay.expire.checkout-window-minutes=5",
      "ecpay.expire.after-minutes=35",
      "ecpay.expire.recheck-after-minutes=5",
      "ecpay.expire.interval-ms=86400000",
      "app.public-url=http://localhost"
    })
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class EcpayExpiryEndToEndTest {
  private static final String KEY = "test-key-g04";
  private static final String IV = "test-iv-g04";
  private static final FakeEcpayServer FAKE = FakeEcpayServer.start("3002607", KEY, IV);

  @DynamicPropertySource
  static void ecpay(DynamicPropertyRegistry registry) {
    registry.add("ecpay.query-url", FAKE::queryUrl);
  }

  @LocalServerPort int port;
  @Autowired Orders orders;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactions;
  @Autowired ReconciliationService service;
  final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void reset() {
    db.update("delete from payment_anomalies");
    db.update("delete from payment_expiries");
    db.update("delete from payment_reconciliations");
    db.update("delete from payment_events");
    db.update("delete from order_item_promotions");
    db.update("delete from order_discounts");
    db.update("delete from order_item_options");
    db.update("delete from order_items");
    db.update("delete from orders");
    FAKE.respond(FakeEcpayServer.Scenario.NOT_FOUND);
  }

  @AfterEach
  void logsAreSafe(CapturedOutput output) {
    assertThat(output.getAll()).doesNotContain(KEY, IV, "\tat ");
  }

  @AfterAll
  static void close() {
    FAKE.close();
  }

  @Test
  void unpaidAndSimulatedOrdersExpireButPaidOrderSettles(CapturedOutput output) {
    String unpaid = oldOrder("ECPAY");
    FAKE.respond(FakeEcpayServer.Scenario.NOT_FOUND);
    service.expireScheduled();
    assertThat(status(unpaid)).isEqualTo("CANCELLED");
    assertThat(count("payment_expiries", unpaid)).isOne();
    assertThat(lastOutcome(unpaid)).isEqualTo("STILL_UNPAID");
    assertThat(output.getAll()).contains("候選 1 筆，作廢 1、已付款 0、保留 0、略過 0");

    String simulated = oldOrder("ECPAY");
    FAKE.respond(FakeEcpayServer.Scenario.SIMULATED);
    service.expireScheduled();
    assertThat(status(simulated)).isEqualTo("CANCELLED");
    assertThat(lastOutcome(simulated)).isEqualTo("SIMULATED");
    assertThat(output.getAll()).contains("候選 1 筆，作廢 1、已付款 0、保留 0、略過 0");

    String paid = oldOrder("ECPAY");
    FAKE.respond(FakeEcpayServer.Scenario.PAID);
    service.expireScheduled();
    assertThat(status(paid)).isEqualTo("PAID");
    assertThat(count("payment_expiries", paid)).isZero();
    assertThat(output.getAll()).contains("候選 1 筆，作廢 0、已付款 1、保留 0、略過 0");
  }

  @Test
  void uncertainQueryKeepsPendingThenLaterNotFoundExpires(CapturedOutput output) {
    for (var scenario :
        List.of(
            FakeEcpayServer.Scenario.TIMEOUT,
            FakeEcpayServer.Scenario.HTTP_500,
            FakeEcpayServer.Scenario.BAD_MAC,
            FakeEcpayServer.Scenario.AMOUNT_MISMATCH)) {
      String id = oldOrder("ECPAY");
      FAKE.respond(scenario);
      service.expireScheduled();
      assertThat(status(id)).isEqualTo("PENDING_PAYMENT");
      assertThat(count("payment_expiries", id)).isZero();
      assertThat(output.getAll()).contains("候選 1 筆，作廢 0、已付款 0、保留 1、略過 0");
      if (scenario == FakeEcpayServer.Scenario.BAD_MAC)
        assertThat(lastDetail(id)).isEqualTo("綠界查單驗證或連線失敗，訂單未變更");
      FAKE.respond(FakeEcpayServer.Scenario.NOT_FOUND);
      service.expireScheduled();
      assertThat(status(id)).isEqualTo("CANCELLED");
    }
  }

  @Test
  void connectionRefusalKeepsPendingAndRecentOrCashOrdersAreNotQueried() throws Exception {
    int closedPort;
    try (var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    var refused =
        new EcpayTradeQuery(
            "3002607", KEY, IV, "stage", "http://127.0.0.1:" + closedPort + "/query", 500);
    var isolated =
        new ReconciliationService(
            orders,
            refused,
            db,
            transactions,
            true,
            true,
            "3002607",
            KEY,
            IV,
            0,
            1,
            50,
            true,
            5,
            35,
            5,
            86400000);
    String id = oldOrder("ECPAY");
    isolated.expireScheduled();
    assertThat(status(id)).isEqualTo("PENDING_PAYMENT");

    int before = FAKE.receivedQueries().size();
    createOrder("ECPAY");
    oldOrder("CASH");
    service.expireScheduled();
    assertThat(FAKE.receivedQueries()).hasSize(before + 1); // only the old pending ECPAY order
  }

  @Test
  void callbackBeforeExpiryWinsWithoutAnotherQuery() throws Exception {
    String id = createOrder("ECPAY");
    assertThat(postCallback(id, Map.of()).body()).isEqualTo("1|OK");
    age(id, 36);
    int before = FAKE.receivedQueries().size();
    service.expireScheduled();
    assertThat(status(id)).isEqualTo("PAID");
    assertThat(FAKE.receivedQueries()).hasSize(before);
  }

  @Test
  void callbackAfterExpiryIsAcknowledgedAndCreatesOneRefundAnomaly() throws Exception {
    String id = expireOrder();
    var callback = callback(id, Map.of());
    assertThat(postForm(callback).body()).isEqualTo("1|OK");
    assertThat(status(id)).isEqualTo("CANCELLED");
    assertThat(db.queryForObject("select paid_at from orders where id=?", Long.class, id)).isNull();
    assertThat(count("payment_anomalies", id)).isOne();
    assertThat(count("payment_events", id)).isOne();
    assertThat(postForm(callback).body()).isEqualTo("1|OK");
    assertThat(count("payment_anomalies", id)).isOne();
    makeRecheckable(id, 6);
    FAKE.respond(FakeEcpayServer.Scenario.PAID);
    service.expireScheduled();
    assertThat(count("payment_anomalies", id)).isOne();

    String mismatch = expireOrder();
    assertThat(postCallback(mismatch, Map.of("TradeAmt", "281")).statusCode()).isEqualTo(400);
    assertThat(count("payment_anomalies", mismatch)).isZero();
  }

  @Test
  void cancelledManualReconciliationReturnsConflictWithoutQuery() throws Exception {
    String id = expireOrder();
    int before = FAKE.receivedQueries().size();
    var manager = new BrowserSession();
    manager.login("manager@coffee.local", "CoffeeDemo!2026");
    assertThat(manager.request("POST", "/api/payments/reconciliation/" + id, null).statusCode())
        .isEqualTo(409);
    assertThat(FAKE.receivedQueries()).hasSize(before);
  }

  @Test
  void recheckRecordsUnpaidPaidMismatchAndGiveUpOutcomes(CapturedOutput output) {
    String unpaid = expireOrder();
    makeRecheckable(unpaid, 6);
    FAKE.respond(FakeEcpayServer.Scenario.NOT_FOUND);
    service.expireScheduled();
    assertThat(expiryOutcome(unpaid)).isEqualTo("UNPAID");
    int afterUnpaid = FAKE.receivedQueries().size();
    service.expireScheduled();
    assertThat(FAKE.receivedQueries()).hasSize(afterUnpaid);

    String paid = expireOrder();
    makeRecheckable(paid, 6);
    FAKE.respond(FakeEcpayServer.Scenario.PAID);
    service.expireScheduled();
    assertThat(expiryOutcome(paid)).isEqualTo("PAID");
    assertThat(status(paid)).isEqualTo("CANCELLED");
    assertThat(count("payment_anomalies", paid)).isOne();

    String mismatch = expireOrder();
    makeRecheckable(mismatch, 6);
    FAKE.respond(FakeEcpayServer.Scenario.AMOUNT_MISMATCH);
    service.expireScheduled();
    assertThat(expiryOutcome(mismatch)).isEqualTo("PAID");
    assertThat(
            db.queryForObject(
                "select trade_amount from payment_anomalies where order_id=?", Integer.class, mismatch))
        .isEqualTo(281);

    String failed = expireOrder();
    makeRecheckable(failed, 6);
    FAKE.respond(FakeEcpayServer.Scenario.BAD_MAC);
    service.expireScheduled();
    assertThat(
            db.queryForObject(
                "select recheck_attempts from payment_expiries where order_id=?", Integer.class, failed))
        .isOne();
    makeRecheckable(failed, 1500);
    service.expireScheduled();
    assertThat(expiryOutcome(failed)).isEqualTo("GAVE_UP");
    assertThat(output.getAll()).contains("綠界作廢後複查放棄：訂單 " + failed);
  }

  private String expireOrder() {
    String id = oldOrder("ECPAY");
    FAKE.respond(FakeEcpayServer.Scenario.NOT_FOUND);
    service.expireScheduled();
    assertThat(status(id)).isEqualTo("CANCELLED");
    return id;
  }

  private String oldOrder(String paymentMethod) {
    String id = createOrder(paymentMethod);
    age(id, 36);
    return id;
  }

  private String createOrder(String paymentMethod) {
    return orders
        .create(
            identity.find("customer"),
            new Orders.Create(
                "taipei",
                "TAKEAWAY",
                paymentMethod,
                "",
                null,
                List.of(new Orders.LineInput("latte", 2, List.of("temp-hot", "sugar-none"))), null),
            UUID.randomUUID().toString())
        .id();
  }

  private void age(String id, long minutes) {
    db.update("update orders set created_at=? where id=?", System.currentTimeMillis() - minutes * 60000, id);
  }

  private void makeRecheckable(String id, long minutes) {
    db.update(
        "update payment_expiries set expired_at=?,rechecked_at=null,recheck_outcome=null where order_id=?",
        System.currentTimeMillis() - minutes * 60000,
        id);
  }

  private HttpResponse<String> postCallback(String id, Map<String, String> overrides)
      throws Exception {
    return postForm(callback(id, overrides));
  }

  private Map<String, String> callback(String id, Map<String, String> overrides) {
    return FAKE.signedCallback(
        Map.of("MerchantID", "3002607", "MerchantTradeNo", id, "TotalAmount", "280"),
        overrides);
  }

  private HttpResponse<String> postForm(Map<String, String> fields) throws Exception {
    String body =
        fields.entrySet().stream()
            .map(
                e ->
                    URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(base() + "/api/payments/ecpay/callback"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private String status(String id) {
    return db.queryForObject("select status from orders where id=?", String.class, id);
  }

  private int count(String table, String id) {
    return db.queryForObject(
        "select count(*) from " + table + " where order_id=?", Integer.class, id);
  }

  private String lastOutcome(String id) {
    return db.queryForObject(
        "select outcome from payment_reconciliations where order_id=? order by queried_at desc,id desc limit 1",
        String.class,
        id);
  }

  private String lastDetail(String id) {
    return db.queryForObject(
        "select detail from payment_reconciliations where order_id=? order by queried_at desc,id desc limit 1",
        String.class,
        id);
  }

  private String expiryOutcome(String id) {
    return db.queryForObject(
        "select recheck_outcome from payment_expiries where order_id=?", String.class, id);
  }

  private String base() {
    return "http://localhost:" + port;
  }

  class BrowserSession {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;
    String header;

    Response request(String method, String path, String body) throws Exception {
      var builder = HttpRequest.newBuilder(URI.create(base() + path));
      if (body != null) builder.header("Content-Type", "application/json");
      if (!"GET".equals(method) && token != null) builder.header(header, token);
      var response =
          client.send(
              builder
                  .method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    }

    JsonNode call(String method, String path, String body) throws Exception {
      var response = request(method, path, body);
      assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
      return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    void login(String username, String password) throws Exception {
      var csrf = call("GET", "/api/auth/csrf", null);
      token = csrf.get("token").asText();
      header = csrf.get("headerName").asText();
      call(
          "POST",
          "/api/auth/login",
          json.writeValueAsString(Map.of("username", username, "password", password)));
      csrf = call("GET", "/api/auth/csrf", null);
      token = csrf.get("token").asText();
      header = csrf.get("headerName").asText();
    }
  }

  record Response(int statusCode, String body) {}
}
