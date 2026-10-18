package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.catalog.api.Catalog;
import com.coffee.identity.api.Identity;
import com.coffee.orders.api.CashSessions;
import com.coffee.orders.api.Orders;
import com.coffee.shared.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:g03-refund;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "server.servlet.session.cookie.secure=false"
})
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderRefundTest {
  @LocalServerPort int port;
  @Autowired Orders orders;
  @Autowired CashSessions sessions;
  @Autowired Identity identity;
  @Autowired Catalog catalog;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactionManager;
  final ObjectMapper json = new ObjectMapper();
  final Map<String, Browser> browsers = new HashMap<>();

  @BeforeAll void login() throws Exception {
    for (String name : List.of("manager", "manager2", "hq", "cashier", "customer")) {
      Browser browser = new Browser();
      browser.login(name);
      browsers.put(name, browser);
    }
  }

  @BeforeEach void reset() {
    db.update("update orders set refunded_at=null,refunded_by=null,refund_reason=null,"
        + "refund_provider_note=null,refund_cash_session_id=null,cash_session_id=null");
    db.update("delete from cash_sessions");
    db.update("delete from branch_product_stock_reservation");
    db.update("delete from branch_product_stock");
    db.update("delete from order_discounts");
    db.update("delete from discounts");
    db.update("update products set price=100 where id='latte'");
  }

  @Test void migrationAndDefaultRolesMatchContract() throws Exception {
    try (var input = getClass().getResourceAsStream("/db/migration/V17__order_refunds.sql")) {
      assertThat(input).isNotNull();
      String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(sql.split("CREATE INDEX", -1)).hasSize(6);
      assertThat(sql.split("CHECK\\(", -1)).hasSize(8);
      for (String name : List.of("ck_orders_refund", "ck_invoice_reversals_attempts",
          "ck_invoice_reversals_claim", "ck_invoice_reversals_kind", "ck_invoice_reversals_status",
          "ck_invoice_reversals_done", "ck_invoice_reversals_inflight")) {
        assertThat(sql).contains("CONSTRAINT " + name);
        assertThat(db.queryForObject("select count(*) from information_schema.table_constraints"
            + " where lower(constraint_name)=?", Integer.class, name)).isEqualTo(1);
      }
    }
    assertThat(Identity.PERMISSIONS).contains("ORDER_REFUND");
    assertThat(db.queryForList("select role_code from role_permissions where permission='ORDER_REFUND'"
        + " order by role_code", String.class)).containsExactly("HQ", "MANAGER");
    assertThat(identity.find("cashier").can("ORDER_REFUND")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PAID", "PREPARING", "READY", "COMPLETED"})
  void managerRefundsEveryPaidStatusWithoutChangingIt(String status) throws Exception {
    String id = paid("taipei", "CASH");
    db.update("update orders set status=? where id=?", status, id);
    var response = refund("manager", id, "  做錯餐點  ", "忽略的紀錄");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode o = json.readTree(response.body());
    assertThat(o.path("status").asText()).isEqualTo(status);
    assertThat(o.path("refundedAt").asLong()).isPositive();
    assertThat(o.path("refundedBy").asText()).isEqualTo("manager");
    assertThat(o.path("refundReason").asText()).isEqualTo("做錯餐點");
    assertThat(row(id).get("refund_provider_note")).isNull();
    assertThat(auditCount(id)).isEqualTo(1);
    assertThat(db.queryForObject("select summary from audit_log where action='ORDER_REFUNDED'"
        + " and target_id=?", String.class, id)).isEqualTo("整筆退款 100 元（現金）：做錯餐點");
    assertThat(orders.get(identity.find("manager"), id).refundedAt()).isNotNull();
    assertThat(orders.page(identity.find("manager"),
        new Orders.Query(null, null, null, null, id, null, 10)).items().get(0).refundedAt()).isNotNull();
    expect(refund("manager", id, "再退", null), 409, "這筆訂單已經退款");
    expect(browsers.get("cashier").request("POST", "/orders/" + id + "/cash", "{\"tendered\":100}", true),
        409, "這筆訂單已經退款");
    expect(browsers.get("manager").request("PATCH", "/orders/" + id + "/status", "{\"status\":\"READY\"}", true),
        409, "這筆訂單已經退款");
    assertThat(auditCount(id)).isEqualTo(1);
  }

  @Test void onlineRefundRequiresManualProviderNoteAndNeverTrustsAmount() throws Exception {
    String id = paid("taipei", "ECPAY");
    for (String note : Arrays.asList(null, " ", "x".repeat(201))) {
      expect(refund("manager", id, "退單", note), 400,
          "請先到綠界廠商後台完成退刷，並填寫退刷紀錄");
      assertThat(row(id).get("refunded_at")).isNull();
    }
    var response = browsers.get("manager").request("POST", "/orders/" + id + "/refund",
        "{\"reason\":\"退單\",\"providerNote\":\" 後台已退刷 #123 \",\"amount\":1}", true);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).path("total").asInt()).isEqualTo(100);
    assertThat(row(id).get("refund_provider_note")).isEqualTo("後台已退刷 #123");
    assertThat(row(id).get("refund_cash_session_id")).isNull();
    assertThat(db.queryForObject("select summary from audit_log where action='ORDER_REFUNDED'"
        + " and target_id=?", String.class, id)).isEqualTo("整筆退款 100 元（線上付款）：退單");
  }

  @Test void authorizationCsrfAndCrossBranchDenialsLeaveEverythingUnchanged() throws Exception {
    String id = paid("taipei", "CASH");
    var session = sessions.open(identity.find("cashier"), new CashSessions.Open("taipei", 1000, ""));
    Map<String, Object> before = row(id);
    var cashBefore = sessions.get(identity.find("manager"), session.id());
    int auditsBefore = db.queryForObject("select count(*) from audit_log", Integer.class);
    for (String actor : List.of("manager2", "cashier", "customer")) {
      assertThat(refund(actor, id, "越權", null).statusCode()).isEqualTo(403);
    }
    Browser anonymous = new Browser();
    anonymous.refresh(); // Isolate authentication from the independent CSRF gate.
    assertThat(anonymous.request("POST", "/orders/" + id + "/refund", "{}", true).statusCode()).isEqualTo(401);
    assertThat(browsers.get("manager").request("POST", "/orders/" + id + "/refund", "{}", false).statusCode()).isEqualTo(403);
    assertThat(row(id)).isEqualTo(before);
    assertThat(sessions.get(identity.find("manager"), session.id())).isEqualTo(cashBefore);
    assertThat(db.queryForObject("select count(*) from audit_log", Integer.class)).isEqualTo(auditsBefore);
    expect(refund("hq", "missing", "無此訂單", null), 404, "找不到訂單");
    assertThat(refund("hq", paid("banqiao", "CASH"), "跨店退款", null).statusCode()).isEqualTo(200);
  }

  @Test void invalidReasonAndUnpaidCancelledOrdersAreRejected() throws Exception {
    String paid = paid("taipei", "CASH");
    for (String reason : Arrays.asList(null, " ", "字".repeat(201))) {
      expect(refund("manager", paid, reason, null), 400, "請填寫 200 字以內的退款原因");
    }
    String pending = create("taipei", "CASH", null).id();
    expect(refund("manager", pending, "退單", null), 409, "這筆訂單尚未付款，不能退款");
    orders.transition(identity.find("cashier"), pending, "CANCELLED");
    expect(refund("manager", pending, "退單", null), 409, "這筆訂單尚未付款，不能退款");
    String expired = create("taipei", "ECPAY", null).id();
    assertThat(orders.expireOnline(expired)).isTrue();
    expect(refund("manager", expired, "退單", "已退刷"), 409, "這筆訂單尚未付款，不能退款");
    assertThat(auditCount(paid)).isZero();
    assertThat(refund("manager", paid, "字".repeat(200), null).statusCode()).isEqualTo(200);
  }

  @Test void concurrentHttpRefundsHaveExactlyOneWinnerAndOneAudit() throws Exception {
    String id = paid("taipei", "CASH");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) results.add(executor.submit(() -> {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
        return refund("manager", id, "重複退單", null).statusCode();
      }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(List.of(results.get(0).get(15, TimeUnit.SECONDS), results.get(1).get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 409);
      assertThat(auditCount(id)).isEqualTo(1);
    } finally { start.countDown(); executor.shutdownNow(); }
  }

  @Test void refundDeductsCurrentSessionAndClosedAmountsStayFrozen() throws Exception {
    Actor manager = identity.find("manager");
    var a = sessions.open(manager, new CashSessions.Open("taipei", 1000, "A"));
    String first = paid("taipei", "CASH");
    assertThat(sessions.get(manager, a.id()).expectedAmount()).isEqualTo(1100);
    assertThat(refund("manager", first, "退單", null).statusCode()).isEqualTo(200);
    var live = sessions.get(manager, a.id());
    assertThat(live.expectedAmount()).isEqualTo(1000);
    assertThat(live.cashRevenue()).isEqualTo(100);
    assertThat(live.refundAmount()).isEqualTo(100);
    assertThat(live.refundOrders()).isEqualTo(1);
    String second = paid("taipei", "CASH");
    var closed = sessions.close(manager, a.id(), new CashSessions.Close(1100, ""));
    assertThat(closed.expectedAmount()).isEqualTo(1100);
    assertThat(closed.cashRevenue()).isEqualTo(200);
    var b = sessions.open(manager, new CashSessions.Open("taipei", 500, "B"));
    assertThat(refund("manager", second, "跨班退單", null).statusCode()).isEqualTo(200);
    assertThat(sessions.get(manager, a.id())).isEqualTo(closed);
    var bLive = sessions.get(manager, b.id());
    assertThat(bLive.expectedAmount()).isEqualTo(400);
    assertThat(bLive.cashRevenue()).isZero();
    assertThat(bLive.refundAmount()).isEqualTo(100);
    assertThat(sessions.close(manager, b.id(), new CashSessions.Close(400, "")).expectedAmount()).isEqualTo(400);
  }

  @Test void unassignedRefundsUseRefundTimeRatherThanOriginalPaymentTime() throws Exception {
    Actor manager = identity.find("manager");
    String id = paid("taipei", "CASH");
    db.update("update orders set paid_at=1000 where id=?", id);
    var before = sessions.search(manager, new CashSessions.Query("taipei", 2000L, 4000L, null, 50));
    assertThat(refund("manager", id, "未歸班退款", null).statusCode()).isEqualTo(200);
    db.update("update orders set refunded_at=3000 where id=?", id);
    var after = sessions.search(manager, new CashSessions.Query("taipei", 2000L, 4000L, null, 50));
    assertThat(after.unassignedCashRevenue() - before.unassignedCashRevenue()).isEqualTo(-100);
    assertThat(after.unassignedOrderCount() - before.unassignedOrderCount()).isEqualTo(-1);
    assertThat(row(id).get("refund_cash_session_id")).isNull();
    assertThat(sessions.search(manager, new CashSessions.Query("taipei", 1000L, 2000L, null, 50))
        .unassignedCashRevenue()).isGreaterThanOrEqualTo(100);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void refundAndCloseSerializeOnBranchInEitherOrder(boolean refundFirst) throws Exception {
    Actor manager = identity.find("manager");
    var session = sessions.open(manager, new CashSessions.Open("taipei", 1000, "競態"));
    String id = paid("taipei", "CASH");
    CountDownLatch holding = new CountDownLatch(1), attempting = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
        db.queryForObject("select id from branches where id='taipei' for update", String.class);
        holding.countDown();
        await(release);
        if (refundFirst) orders.refund(manager, id, new Orders.RefundRequest("先退款", null));
        else sessions.close(manager, session.id(), new CashSessions.Close(1100, ""));
      }));
      Future<?> second = executor.submit(() -> {
        await(holding); attempting.countDown();
        if (refundFirst) sessions.close(manager, session.id(), new CashSessions.Close(1000, ""));
        else orders.refund(manager, id, new Orders.RefundRequest("交班後退款", null));
      });
      await(attempting); release.countDown();
      first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
      var closed = sessions.get(manager, session.id());
      assertThat(closed.status()).isEqualTo("CLOSED");
      assertThat(closed.expectedAmount()).isEqualTo(refundFirst ? 1000 : 1100);
      assertThat(closed.refundAmount()).isEqualTo(refundFirst ? 100 : 0);
      assertThat(row(id).get("refund_cash_session_id")).isEqualTo(refundFirst ? session.id() : null);
      assertThat(auditCount(id)).isEqualTo(1);
    } finally { release.countDown(); executor.shutdownNow(); }
  }

  static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
    }
  }

  @Test void refundsNeverRestoreStockOrDiscountRedemptions() throws Exception {
    catalog.setStock(identity.find("cashier"), "taipei", "latte", 2);
    long now = System.currentTimeMillis();
    db.update("insert into discounts(id,code,name,kind,percent,amount,min_subtotal,branch_id,"
        + "starts_at,ends_at,max_redemptions,redeemed_count,active,created_at,updated_at)"
        + " values('refund-discount','REFUND-10','退款測試','AMOUNT',0,10,0,null,null,null,10,0,true,?,?)", now, now);
    var order = create("taipei", "CASH", "REFUND-10");
    orders.cash(identity.find("cashier"), order.id(), order.total());
    var stock = db.queryForList("select * from branch_product_stock");
    assertThat(refund("manager", order.id(), "退款不回補", null).statusCode()).isEqualTo(200);
    assertThat(db.queryForList("select * from branch_product_stock")).isEqualTo(stock);
    assertThat(db.queryForObject("select redeemed_count from discounts where id='refund-discount'", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("select count(*) from branch_product_stock_reservation where order_id=?", Integer.class, order.id())).isEqualTo(1);
  }

  @Test void internalRefundCursorIsInclusiveAtInitialBoundaryAndKeysetPaginated() throws Exception {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      String id = paid("taipei", "CASH"); ids.add(id);
      assertThat(refund("manager", id, "游標測試", null).statusCode()).isEqualTo(200);
    }
    Collections.sort(ids);
    db.update("update orders set refunded_at=1000 where id=?", ids.get(0));
    for (int i = 1; i < 4; i++) db.update("update orders set refunded_at=2000,paid_at=5000 where id=?", ids.get(i));
    var first = orders.refundedCandidates(2000, "", 2000, 2);
    assertThat(first).extracting(Orders.RefundedCandidate::orderId).containsExactly(ids.get(1), ids.get(2));
    assertThat(orders.refundedCandidates(2000, ids.get(2), 2000, 2))
        .extracting(Orders.RefundedCandidate::orderId).containsExactly(ids.get(3));
    assertThat(orders.invoiceCandidate(ids.get(1)).orElseThrow().refundedAt()).isEqualTo(2000L);
    assertThat(orders.invoiceCandidates(5000, "", 5000, 200))
        .filteredOn(o -> o.orderId().equals(ids.get(1))).singleElement()
        .satisfies(o -> assertThat(o.refundedAt()).isEqualTo(2000L));
  }

  Orders.Order create(String branch, String method, String discount) {
    return orders.create(identity.find(branch.equals("taipei") ? "cashier" : "manager2"),
        new Orders.Create(branch, "TAKEAWAY", method, "", discount,
            List.of(new Orders.LineInput("latte", 1, List.of("temp-hot", "sugar-none"))), null), UUID.randomUUID().toString());
  }
  String paid(String branch, String method) {
    var o = create(branch, method, null);
    if (method.equals("CASH")) orders.cash(identity.find(branch.equals("taipei") ? "cashier" : "manager2"), o.id(), o.total());
    else orders.confirmOnline(o.id(), o.total(), "REFUND" + UUID.randomUUID());
    return o.id();
  }
  Map<String, Object> row(String id) { return db.queryForMap("select * from orders where id=?", id); }
  int auditCount(String id) { return db.queryForObject("select count(*) from audit_log where action='ORDER_REFUNDED' and target_id=?", Integer.class, id); }
  HttpResponse<String> refund(String who, String id, String reason, String note) throws Exception {
    Map<String, Object> body = new HashMap<>(); body.put("reason", reason); body.put("providerNote", note);
    return browsers.get(who).request("POST", "/orders/" + id + "/refund", json.writeValueAsString(body), true);
  }
  void expect(HttpResponse<String> response, int status, String message) throws Exception {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    assertThat(json.readTree(response.body()).path("message").asText()).isEqualTo(message);
  }
  class Browser {
    final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    String token, header;
    HttpResponse<String> request(String method, String path, String body, boolean csrf) throws Exception {
      var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path));
      if (body != null) b.header("Content-Type", "application/json");
      if (csrf && token != null && !method.equals("GET")) b.header(header, token);
      return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    void refresh() throws Exception {
      var response = request("GET", "/auth/csrf", null, false);
      assertThat(response.statusCode()).isEqualTo(200);
      var csrf = json.readTree(response.body()); token = csrf.path("token").asText(); header = csrf.path("headerName").asText();
    }
    void login(String user) throws Exception {
      refresh();
      assertThat(request("POST", "/auth/login", json.writeValueAsString(Map.of("username", user + "@coffee.local", "password", "CoffeeDemo!2026")), true).statusCode()).isEqualTo(200);
      refresh();
    }
  }
}
