package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import static com.coffee.app.ecpay.FakeEinvoiceServer.Scenario.*;

import com.coffee.app.ecpay.FakeEinvoiceServer;
import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.payments.internal.InvoiceService;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:g02-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "server.servlet.session.cookie.secure=false", "ecpay.invoice.enabled=true",
    "ecpay.invoice.environment=stage", "ecpay.invoice.merchant-id=fake-api-merchant",
    "ecpay.invoice.hash-key=ApiInvoiceKey123", "ecpay.invoice.hash-iv=ApiInvoiceIv1234",
    "orders.invoice-required=true", "ecpay.invoice.timeout-ms=1000",
    "ecpay.invoice.interval-ms=3600000", "ecpay.invoice.max-attempts=3"
})
@ActiveProfiles("dev")
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceApiTest {
  private static final String KEY = "ApiInvoiceKey123", IV = "ApiInvoiceIv1234";
  private static final FakeEinvoiceServer fake = startFake();
  static FakeEinvoiceServer startFake() {
    try { return new FakeEinvoiceServer(KEY, IV); }
    catch (Exception failure) { throw new IllegalStateException("測試伺服器啟動失敗"); }
  }
  @DynamicPropertySource static void local(DynamicPropertyRegistry r) { r.add("ecpay.invoice.base-url", fake::baseUrl); }
  @LocalServerPort int port;
  @Autowired Orders orders;
  @Autowired Identity identity;
  @Autowired InvoiceService service;
  @Autowired JdbcTemplate db;
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, Browser> sessions = new HashMap<>();

  @BeforeAll void login() throws Exception {
    for (String user : List.of("hq", "manager", "manager2", "cashier", "customer")) {
      Browser browser = new Browser();
      browser.login(user + "@coffee.local"); sessions.put(user, browser);
    }
  }
  @BeforeEach void reset() {
    db.update("delete from invoices"); db.update("delete from invoice_cursors");
    db.update("update orders set paid_at=null"); fake.reset();
  }
  @AfterAll void close() { fake.close(); }
  @AfterEach void logs(CapturedOutput output) {
    assertThat(output.getAll()).doesNotContain(KEY, IV, "api-private@example.test", "\tat ");
  }

  class Browser {
    final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    String token, header;
    HttpResponse<String> request(String method, String path, String body, boolean csrf) throws Exception {
      var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path));
      if (body != null) b.header("Content-Type", "application/json");
      if (csrf && token != null && !method.equals("GET")) b.header(header, token);
      var result = http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
      assertThat(result.body()).doesNotContain(KEY, IV, "fake-api-merchant", fake.baseUrl());
      return result;
    }
    JsonNode call(String method, String path, String body) throws Exception {
      var result = request(method, path, body, true);
      assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(200);
      return json.readTree(result.body());
    }
    void login(String username) throws Exception {
      refresh();
      call("POST", "/auth/login", json.writeValueAsString(Map.of("username", username, "password", "CoffeeDemo!2026")));
      refresh();
    }
    void refresh() throws Exception {
      var csrf = call("GET", "/auth/csrf", null);
      token = csrf.get("token").asText(); header = csrf.get("headerName").asText();
    }
  }
  String order(String branch, boolean customer, boolean invoice) {
    var o = orders.create(identity.find(customer ? "customer" : branch.equals("taipei") ? "cashier" : "manager2"),
        new Orders.Create(branch, "TAKEAWAY", "CASH", "", null,
            List.of(new Orders.LineInput("latte", 1, List.of("temp-hot", "sugar-none"))),
            new Orders.InvoiceRequest("CLOUD", "api-private@example.test", null, null)), UUID.randomUUID().toString());
    orders.cash(identity.find(branch.equals("taipei") ? "cashier" : "manager2"), o.id(), o.total());
    if (invoice) db.update("insert into invoices(order_id,branch_id,amount,status,attempts,created_at,updated_at) "
        + "values(?,?,?,'FAILED',3,0,1)", o.id(), branch, o.total());
    return o.id();
  }
  String path(String id) { return "/payments/invoices/order/" + id; }
  String retry(String id) { return path(id) + "/retry"; }
  Map<String, Object> row(String id) { return db.queryForMap("select * from invoices where order_id=?", id); }

  @Test void readsUseOrderAuthorizationAndNoneDoesNotLeakData() throws Exception {
    String own = order("taipei", true, true), other = order("taipei", false, true);
    var customer = sessions.get("customer");
    assertThat(customer.call("GET", path(own), null).path("orderId").asText()).isEqualTo(own);
    int existing = customer.request("GET", "/orders/" + other, null, true).statusCode();
    assertThat(existing).isIn(403, 404);
    assertThat(customer.request("GET", path(other), null, true).statusCode()).isEqualTo(existing);
    assertThat(sessions.get("manager2").request("GET", path(own), null, true).statusCode()).isEqualTo(403);
    sessions.get("hq").call("GET", path(own), null);
    assertThat(new Browser().request("GET", path(own), null, false).statusCode()).isEqualTo(401);
    String none = order("taipei", true, false);
    var response = customer.call("GET", path(none), null);
    assertThat(response.path("status").asText()).isEqualTo("NONE");
    response.fields().forEachRemaining(field -> { if (!field.getKey().equals("status")) assertThat(field.getValue().isNull()).isTrue(); });
    assertThat(customer.request("GET", path("missing"), null, true).statusCode()).isEqualTo(404);
  }

  @Test void failedListIsStaffScopedSortedAndTruncated() throws Exception {
    String a = order("taipei", true, true), b = order("banqiao", true, true);
    db.update("update invoices set updated_at=2 where order_id=?", b);
    var all = sessions.get("hq").call("GET", "/payments/invoices/failed", null);
    assertThat(all.path("items").size()).isEqualTo(2);
    assertThat(all.path("items").get(0).path("orderId").asText()).isEqualTo(b);
    assertThat(sessions.get("manager").call("GET", "/payments/invoices/failed", null).path("items").size()).isEqualTo(1);
    assertThat(sessions.get("hq").call("GET", "/payments/invoices/failed?branchId=taipei", null).path("items").get(0).path("orderId").asText()).isEqualTo(a);
    assertThat(sessions.get("manager").request("GET", "/payments/invoices/failed?branchId=banqiao", null, true).statusCode()).isEqualTo(403);
    for (String user : List.of("customer", "cashier"))
      assertThat(sessions.get(user).request("GET", "/payments/invoices/failed", null, true).statusCode()).isEqualTo(403);
    Actor forgedCustomer = new Actor("customer", "x", "x", "CUSTOMER", "SELF", null, Set.of("PAYMENT_RECONCILE"));
    assertThatThrownBy(() -> service.failed(forgedCustomer, null)).isInstanceOf(Problem.class);
    for (int n = 0; n < 199; n++) order("taipei", false, true);
    var page = sessions.get("hq").call("GET", "/payments/invoices/failed", null);
    assertThat(page.path("items").size()).isEqualTo(200);
    assertThat(page.path("truncated").asBoolean()).isTrue();
  }

  @Test void retryEnforcesCsrfScopeAndStateWithoutSendingHttp() throws Exception {
    String id = order("taipei", true, true);
    var before = row(id);
    assertThat(sessions.get("manager2").request("POST", retry(id), null, true).statusCode()).isEqualTo(403);
    assertThat(row(id)).isEqualTo(before);
    for (String user : List.of("customer", "cashier"))
      assertThat(sessions.get(user).request("POST", retry(id), null, true).statusCode()).isEqualTo(403);
    assertThat(sessions.get("manager").request("POST", retry(id), null, false).statusCode()).isEqualTo(403);
    sessions.get("manager").call("POST", retry(id), null);
    assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("uncertain", true);
    assertThat(fake.received()).isEmpty();
    assertThat(sessions.get("manager").request("POST", retry(id), null, true).statusCode()).isEqualTo(409);
    assertThat(sessions.get("manager").request("POST", retry("missing"), null, true).statusCode()).isEqualTo(404);
    service.issueOne(id);
    assertThat(row(id)).containsEntry("status", "ISSUED");
    assertThat(fake.received().stream().map(r -> r.path().substring(r.path().lastIndexOf('/') + 1))).containsExactly("GetIssue", "Issue");
    assertThat(sessions.get("manager").request("POST", retry(id), null, true).statusCode()).isEqualTo(409);
    var config = sessions.get("customer").call("GET", "/payments/config", null);
    assertThat(config.path("invoice").path("enabled").asBoolean()).isTrue();
    assertThat(config.has("enabled") && config.has("environment") && config.has("expire")).isTrue();
  }

  @Test void retryPreservesUnknownBoundaryUntilSafeQuery() throws Exception {
    String id = order("taipei", true, true);
    db.update("update invoices set status='PENDING',attempts=0,uncertain=false where order_id=?", id);
    fake.respond("Issue", TIMEOUT); fake.respond("GetIssue", TIMEOUT);
    service.issueOne(id); service.issueOne(id); service.issueOne(id);
    assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("attempts", 3).containsEntry("uncertain", true);
    Object bound = row(id).get("inflight_until");
    assertThat(bound).isNotNull();
    sessions.get("manager").call("POST", retry(id), null);
    assertThat(row(id)).containsEntry("attempts", 0).containsEntry("status", "PENDING").containsEntry("inflight_until", bound);
    int calls = fake.received().size(); service.runOnce(System.currentTimeMillis());
    assertThat(fake.received()).hasSize(calls);
    fake.respond("Issue", OK); fake.respond("GetIssue", OK);
    db.update("update invoices set inflight_until=? where order_id=?", System.currentTimeMillis() - 1, id);
    service.issueOne(id);
    assertThat(row(id)).containsEntry("status", "ISSUED");
    assertThat(fake.received().get(calls).path()).endsWith("/GetIssue");
  }
}
