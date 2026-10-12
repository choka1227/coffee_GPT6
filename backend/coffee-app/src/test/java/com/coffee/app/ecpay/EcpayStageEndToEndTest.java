package com.coffee.app.ecpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.payments.internal.EcpayTradeQuery;
import com.coffee.payments.internal.ReconciliationService;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
      "spring.datasource.url=jdbc:h2:mem:ecpay-stage-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "server.servlet.session.cookie.secure=false",
      "ecpay.enabled=true",
      "ecpay.environment=stage",
      "ecpay.merchant-id=3002607",
      "ecpay.hash-key=test-key-g01b",
      "ecpay.hash-iv=test-iv-g01b",
      "ecpay.query-timeout-ms=500",
      "ecpay.reconcile.enabled=true",
      "ecpay.reconcile.interval-ms=86400000",
      "ecpay.reconcile.min-age-minutes=0",
      "app.public-url=http://localhost"
    })
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EcpayStageEndToEndTest {
  private static final String KEY = "test-key-g01b";
  private static final String IV = "test-iv-g01b";
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
  @Autowired ReconciliationService scheduledService;
  final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void resetPayments() {
    db.update("delete from payment_reconciliations");
    db.update("delete from payment_events");
    db.update("delete from order_item_promotions");
    db.update("delete from order_discounts");
    db.update("delete from order_item_options");
    db.update("delete from order_items");
    db.update("delete from orders");
    FAKE.respond(FakeEcpayServer.Scenario.NOT_FOUND);
  }

  @AfterAll
  static void closeServerAndWorkers() {
    FAKE.close();
    assertThat(
            Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("fake-ecpay-")))
        .isEmpty();
  }

  @Test
  void paidQueryIsConfirmedOverRealHttp() {
    assertOutcome(FakeEcpayServer.Scenario.PAID, "CONFIRMED", "PAID");
  }

  @Test
  void notFoundQueryRemainsUnpaidOverRealHttp() {
    assertOutcome(FakeEcpayServer.Scenario.NOT_FOUND, "STILL_UNPAID", "PENDING_PAYMENT");
  }

  @Test
  void amountMismatchQueryIsRejectedOverRealHttp() {
    assertOutcome(FakeEcpayServer.Scenario.AMOUNT_MISMATCH, "AMOUNT_MISMATCH", "PENDING_PAYMENT");
  }

  @Test
  void badMacQueryIsRejectedOverRealHttp() {
    assertOutcome(FakeEcpayServer.Scenario.BAD_MAC, "QUERY_FAILED", "PENDING_PAYMENT");
  }

  @Test
  void timeoutQueryIsRejectedOverRealHttp() {
    assertOutcome(FakeEcpayServer.Scenario.TIMEOUT, "QUERY_FAILED", "PENDING_PAYMENT");
  }

  @Test
  void http500QueryIsRejectedOverRealHttp() {
    assertOutcome(FakeEcpayServer.Scenario.HTTP_500, "QUERY_FAILED", "PENDING_PAYMENT");
  }

  @Test
  void redirectToProductionIsNotFollowed() {
    long started = System.nanoTime();
    assertOutcome(
        FakeEcpayServer.Scenario.REDIRECT_TO_PRODUCTION,
        "QUERY_FAILED",
        "PENDING_PAYMENT");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void connectionRefusedQueryIsRejected() throws Exception {
    int closedPort;
    try (var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    var query =
        new EcpayTradeQuery(
            "3002607", KEY, IV, "stage", "http://127.0.0.1:" + closedPort + "/query", 500);
    var order = createOrder();
    var result = service(query).reconcile(identity.find("manager"), order.id());
    assertThat(result.outcome()).isEqualTo("QUERY_FAILED");
    assertThat(orderStatus(order.id())).isEqualTo("PENDING_PAYMENT");
  }

  @Test
  void checkoutUsesDatabaseTotalAndManualReconcileUsesRealLoginAndCsrf() throws Exception {
    var customer = new BrowserSession();
    customer.login("customer@coffee.local", "CoffeeDemo!2026");
    String id = customerOrder(customer, "taipei");
    JsonNode checkout = customer.call("POST", "/api/payments/ecpay/" + id, null);
    assertSafe(checkout.toString());
    assertThat(checkout.at("/fields/TotalAmount").asText()).isEqualTo("280");
    assertThat(db.queryForObject("select total from orders where id=?", Integer.class, id))
        .isEqualTo(280);

    FAKE.respond(FakeEcpayServer.Scenario.NOT_FOUND);
    int before = FAKE.receivedQueries().size();
    var otherManager = new BrowserSession();
    otherManager.login("manager2@coffee.local", "CoffeeDemo!2026");
    assertThat(
            otherManager.request(
                    "POST", "/api/payments/reconciliation/" + id, null, Map.of())
                .statusCode())
        .isEqualTo(403);
    assertThat(FAKE.receivedQueries()).hasSize(before);

    var manager = new BrowserSession();
    manager.login("manager@coffee.local", "CoffeeDemo!2026");
    var reconciled = manager.call("POST", "/api/payments/reconciliation/" + id, null);
    assertThat(reconciled.get("outcome").asText()).isEqualTo("STILL_UNPAID");
    assertThat(FAKE.receivedQueries()).hasSize(before + 1);
    assertSafe(manager.call("GET", "/api/payments/reconciliation/" + id, null).toString());
    assertSafe(manager.call("GET", "/api/payments/reconciliation/pending", null).toString());
    assertSafe(manager.call("GET", "/api/payments/config", null).toString());
  }

  @Test
  void callbacksCoverSuccessReplayMismatchMacSimulationFailureAndDuplicateParameter()
      throws Exception {
    callbackCase(Map.of(), 200, "PAID");
    callbackCase(Map.of("TradeAmt", "281"), 400, "PENDING_PAYMENT");
    callbackCase(Map.of("SimulatePaid", "1"), 200, "PENDING_PAYMENT");
    callbackCase(Map.of("RtnCode", "10100058"), 200, "PENDING_PAYMENT");

    var customer = new BrowserSession();
    customer.login("customer@coffee.local", "CoffeeDemo!2026");
    String id = customerOrder(customer, "taipei");
    Map<String, String> callback = callbackFor(customer, id, Map.of());
    callback.put("CheckMacValue", "BAD");
    assertThat(postForm("/api/payments/ecpay/callback", callback).statusCode()).isEqualTo(400);
    assertThat(paymentEvents(id)).isZero();
    assertThat(orderStatus(id)).isEqualTo("PENDING_PAYMENT");

    callback = callbackFor(customer, id, Map.of());
    String body = form(callback) + "&TradeAmt=280";
    var duplicated =
        HttpRequest.newBuilder(URI.create(base() + "/api/payments/ecpay/callback"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    assertThat(HttpClient.newHttpClient().send(duplicated, HttpResponse.BodyHandlers.ofString()).body())
        .isEqualTo("0|Invalid notification");
  }

  @Test
  void lostCallbackIsRecoveredThenLateCallbackIsIdempotent() throws Exception {
    var customer = new BrowserSession();
    customer.login("customer@coffee.local", "CoffeeDemo!2026");
    String id = customerOrder(customer, "taipei");
    Map<String, String> checkout = checkoutFields(customer, id);
    FAKE.respond(FakeEcpayServer.Scenario.PAID);
    Thread.sleep(5);
    scheduledService.scheduled();
    assertThat(orderStatus(id)).isEqualTo("PAID");
    assertThat(
            db.queryForObject(
                "select outcome from payment_reconciliations where order_id=?",
                String.class,
                id))
        .isEqualTo("CONFIRMED");
    assertThat(
            db.queryForObject(
                "select trigger_source from payment_reconciliations where order_id=?",
                String.class,
                id))
        .isEqualTo("SCHEDULED");
    long paidAt = db.queryForObject("select paid_at from orders where id=?", Long.class, id);

    var late = FAKE.signedCallback(checkout, Map.of());
    assertThat(postForm("/api/payments/ecpay/callback", late).body()).isEqualTo("1|OK");
    assertThat(db.queryForObject("select paid_at from orders where id=?", Long.class, id))
        .isEqualTo(paidAt);

    var conflict = FAKE.signedCallback(checkout, Map.of("TradeNo", "DIFFERENTTRADE000001"));
    assertThat(postForm("/api/payments/ecpay/callback", conflict).body())
        .isEqualTo("0|Invalid notification");
    assertThat(orderStatus(id)).isEqualTo("PAID");
  }

  @Test
  void fakeServerUsesItsActualIpv4LoopbackPort() {
    URI endpoint = URI.create(FAKE.queryUrl());
    assertThat(endpoint.getHost()).isEqualTo("127.0.0.1");
    assertThat(endpoint.getPort()).isEqualTo(FAKE.port()).isPositive();
  }

  private void callbackCase(Map<String, String> overrides, int status, String orderStatus)
      throws Exception {
    var customer = new BrowserSession();
    customer.login("customer@coffee.local", "CoffeeDemo!2026");
    String id = customerOrder(customer, "taipei");
    Map<String, String> callback = callbackFor(customer, id, overrides);
    var response = postForm("/api/payments/ecpay/callback", callback);
    assertThat(response.statusCode()).isEqualTo(status);
    assertSafe(response.body());
    assertThat(orderStatus(id)).isEqualTo(orderStatus);
    if (overrides.isEmpty()) {
      assertThat(paymentEvents(id)).isEqualTo(1);
      long paidAt = db.queryForObject("select paid_at from orders where id=?", Long.class, id);
      assertThat(postForm("/api/payments/ecpay/callback", callback).body()).isEqualTo("1|OK");
      assertThat(db.queryForObject("select paid_at from orders where id=?", Long.class, id))
          .isEqualTo(paidAt);
    }
  }

  private Map<String, String> callbackFor(
      BrowserSession customer, String id, Map<String, String> overrides) throws Exception {
    return FAKE.signedCallback(checkoutFields(customer, id), overrides);
  }

  private Map<String, String> checkoutFields(BrowserSession customer, String id) throws Exception {
    JsonNode checkout = customer.call("POST", "/api/payments/ecpay/" + id, null);
    return json.convertValue(checkout.get("fields"), new TypeReference<>() {});
  }

  private void assertOutcome(
      FakeEcpayServer.Scenario scenario, String outcome, String expectedStatus) {
    FAKE.respond(scenario);
    var order = createOrder();
    var result = scheduledService.reconcile(identity.find("manager"), order.id());
    assertThat(result.outcome()).isEqualTo(outcome);
    assertThat(orderStatus(order.id())).isEqualTo(expectedStatus);
    assertThat(
            db.queryForObject(
                "select outcome from payment_reconciliations where order_id=?",
                String.class,
                order.id()))
        .isEqualTo(outcome);
    assertThat(FAKE.receivedQueries().get(FAKE.receivedQueries().size() - 1))
        .containsEntry("MerchantID", "3002607")
        .containsEntry("MerchantTradeNo", order.id());
  }

  private Orders.Order createOrder() {
    return orders.create(
        identity.find("customer"),
        new Orders.Create(
            "taipei",
            "TAKEAWAY",
            "ECPAY",
            "",
            null,
            List.of(new Orders.LineInput("latte", 2, List.of("temp-hot", "sugar-none")))),
        UUID.randomUUID().toString());
  }

  private ReconciliationService service(EcpayTradeQuery query) {
    return new ReconciliationService(
        orders, query, db, transactions, true, true, "3002607", KEY, IV, 0, 7, 50);
  }

  private String orderStatus(String id) {
    return db.queryForObject("select status from orders where id=?", String.class, id);
  }

  private int paymentEvents(String id) {
    return db.queryForObject(
        "select count(*) from payment_events where order_id=?", Integer.class, id);
  }

  private String customerOrder(BrowserSession customer, String branch) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "branchId",
                branch,
                "fulfillment",
                "TAKEAWAY",
                "paymentMethod",
                "ECPAY",
                "note",
                "stage e2e",
                "total",
                1,
                "items",
                List.of(
                    Map.of(
                        "productId",
                        "latte",
                        "quantity",
                        2,
                        "optionIds",
                        List.of("temp-hot", "sugar-none"),
                        "unitPrice",
                        1))));
    return customer
        .request(
            "POST", "/api/orders", body, Map.of("Idempotency-Key", UUID.randomUUID().toString()))
        .thenApply(json, "id");
  }

  private HttpResponse<String> postForm(String path, Map<String, String> fields) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base() + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String form(Map<String, String> fields) {
    return fields.entrySet().stream()
        .map(
            entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
        .collect(Collectors.joining("&"));
  }

  private String base() {
    return "http://localhost:" + port;
  }

  private static void assertSafe(String body) {
    assertThat(body).doesNotContain(KEY, IV);
  }

  class BrowserSession {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;
    String header;

    Response request(String method, String path, String body, Map<String, String> extra)
        throws Exception {
      var builder = HttpRequest.newBuilder(URI.create(base() + path));
      if (body != null) builder.header("Content-Type", "application/json");
      if (!"GET".equals(method) && token != null) builder.header(header, token);
      extra.forEach(builder::header);
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
      assertSafe(response.body());
      return new Response(response.statusCode(), response.body());
    }

    JsonNode call(String method, String path, String body) throws Exception {
      var response = request(method, path, body, Map.of());
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

  record Response(int statusCode, String body) {
    String thenApply(ObjectMapper mapper, String field) throws Exception {
      assertThat(statusCode).withFailMessage(body).isEqualTo(200);
      return mapper.readTree(body).get(field).asText();
    }
  }
}
