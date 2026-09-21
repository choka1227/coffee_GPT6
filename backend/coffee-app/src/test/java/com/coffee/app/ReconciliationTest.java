package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.payments.api.*;
import com.coffee.payments.internal.EcpayTradeQuery;
import com.coffee.payments.internal.ReconciliationService;
import com.coffee.shared.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:reconciliation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "ecpay.enabled=true",
      "ecpay.environment=stage",
      "ecpay.merchant-id=3002607",
      "ecpay.hash-key=test-key",
      "ecpay.hash-iv=test-iv",
      "app.public-url=https://coffee.example.test",
      "ecpay.reconcile.enabled=true",
      "ecpay.reconcile.interval-ms=86400000"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class ReconciliationTest {
  @Autowired com.coffee.reporting.internal.ReportService reports;
  @Autowired Orders orders;
  @Autowired Identity identity;
  @Autowired ReconciliationService service;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean TradeQuery query;

  @BeforeEach
  void resetOrders() {
    db.update("delete from payment_reconciliations");
    db.update("delete from payment_events");
    db.update("delete from order_item_options");
    db.update("delete from order_items");
    db.update("delete from orders");
  }

  Actor manager() {
    return identity.find("manager");
  }

  Orders.Order create(String branch, String method) {
    return orders.create(
        identity.find("customer"),
        new Orders.Create(
            branch,
            "TAKEAWAY",
            method,
            "",
            null,
            List.of(new Orders.LineInput("latte", 1, List.of("temp-hot", "sugar-none")))),
        UUID.randomUUID().toString());
  }

  Map<String, String> response(Orders.Order o) {
    Map<String, String> p = new HashMap<>();
    p.put("MerchantID", "3002607");
    p.put("MerchantTradeNo", o.id());
    p.put("TradeStatus", "1");
    p.put("TradeAmt", Integer.toString(o.total()));
    p.put("TradeNo", "T" + o.id());
    p.put(
        "PaymentDate",
        LocalDateTime.now(ZoneId.of("Asia/Taipei"))
            .minusDays(1)
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")));
    return p;
  }

  Map<String, String> sign(Map<String, String> p) {
    p.put("CheckMacValue", CheckMac.sign(p, "test-key", "test-iv"));
    return p;
  }

  void stub(Map<String, String> p) {
    when(query.query(anyString())).thenReturn(sign(p));
  }

  @Test
  void confirmedPreservesTaipeiPaymentTimeAndIdempotentCallback() {
    var o = create("taipei", "ECPAY");
    var p = response(o);
    stub(p);
    var result = service.reconcile(manager(), o.id());
    assertThat(result.outcome()).isEqualTo("CONFIRMED");
    long paidAt =
        LocalDateTime.parse(
                p.get("PaymentDate"), DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
            .atZone(ZoneId.of("Asia/Taipei"))
            .toInstant()
            .toEpochMilli();
    assertThat(orders.paymentSnapshot(o.id()).paidAt()).isEqualTo(paidAt);
    orders.confirmOnline(o.id(), o.total(), p.get("TradeNo"));
    assertThat(orders.paymentSnapshot(o.id()).paidAt()).isEqualTo(paidAt);
    assertThat(service.history(manager(), o.id())).hasSize(1);
  }

  @Test
  void failuresNeverCreditOrders() {
    for (String scenario :
        List.of(
            "unpaid",
            "simulation",
            "amount",
            "signature",
            "merchant",
            "order",
            "overflow",
            "timeout")) {
      var o = create("taipei", "ECPAY");
      var p = response(o);
      String expected = "QUERY_FAILED";
      switch (scenario) {
        case "unpaid" -> {
          p.put("TradeStatus", "0");
          expected = "STILL_UNPAID";
        }
        case "simulation" -> {
          p.put("SimulatePaid", "1");
          expected = "SIMULATED";
        }
        case "amount" -> {
          p.put("TradeAmt", "1");
          expected = "AMOUNT_MISMATCH";
        }
        case "merchant" -> p.put("MerchantID", "other");
        case "order" -> p.put("MerchantTradeNo", "other");
        case "overflow" -> p.put("TradeAmt", "99999999999999");
      }
      reset(query);
      stub(p);
      if (scenario.equals("signature")) p.put("CheckMacValue", "INVALID");
      if (scenario.equals("timeout"))
        when(query.query(anyString())).thenThrow(new Problem(503, "逾時"));
      assertThat(service.reconcile(manager(), o.id()).outcome()).as(scenario).isEqualTo(expected);
      assertThat(orders.paymentSnapshot(o.id()).status()).isEqualTo("PENDING_PAYMENT");
      assertThat(service.history(manager(), o.id())).hasSize(1);
    }
  }

  @Test
  void optionalFieldsRespectOutcomePriorityAndNeverCredit() {
    for (String scenario :
        List.of(
            "unpaid-empty",
            "unpaid-zero",
            "unpaid-missing",
            "simulated-empty",
            "simulated-missing",
            "mismatch-empty",
            "paid-empty",
            "paid-missing",
            "invalid-trade",
            "invalid-amount",
            "overflow-amount")) {
      var o = create("taipei", "ECPAY");
      var p = response(o);
      p.put("TradeNo", "");
      Integer expectedAmount = null;
      String expected = "QUERY_FAILED";
      switch (scenario) {
        case "unpaid-empty", "unpaid-zero", "unpaid-missing" -> {
          p.put("TradeStatus", "0");
          p.put("TradeAmt", scenario.equals("unpaid-zero") ? "0" : "");
          if (scenario.equals("unpaid-missing")) p.remove("TradeAmt");
          expectedAmount = scenario.equals("unpaid-zero") ? 0 : null;
          expected = "STILL_UNPAID";
        }
        case "simulated-empty", "simulated-missing" -> {
          p.put("SimulatePaid", "1");
          p.remove("TradeAmt");
          if (scenario.equals("simulated-empty")) p.put("TradeAmt", "");
          expected = "SIMULATED";
        }
        case "mismatch-empty" -> {
          p.put("TradeAmt", "1");
          expectedAmount = 1;
          expected = "AMOUNT_MISMATCH";
        }
        case "paid-missing" -> p.remove("TradeAmt");
        case "invalid-trade" -> p.put("TradeNo", "bad/trade");
        case "invalid-amount", "overflow-amount" -> {
          p.put("TradeNo", "T" + o.id());
          p.put("TradeAmt", scenario.equals("invalid-amount") ? "invalid" : "2147483648");
        }
      }
      stub(p);
      var result = service.reconcile(manager(), o.id());
      assertThat(result.outcome()).as(scenario).isEqualTo(expected);
      if (expected.equals("AMOUNT_MISMATCH"))
        assertThat(result.detail()).contains("1 元", o.total() + " 元");
      assertThat(orders.paymentSnapshot(o.id()).status()).isEqualTo("PENDING_PAYMENT");
      assertThat(
              db.queryForObject(
                  "select provider_trade_no from orders where id=?", String.class, o.id()))
          .isNull();
      assertThat(
              db.queryForObject(
                  "select provider_trade_no from payment_reconciliations where order_id=?",
                  String.class,
                  o.id()))
          .isEmpty();
      assertThat(service.history(manager(), o.id()))
          .singleElement()
          .extracting(Reconciliation.Attempt::tradeAmount)
          .isEqualTo(expectedAmount);
    }
    assertThat(
            db.queryForObject(
                "select count(*) from orders where provider_trade_no=''", Integer.class))
        .isZero();
  }

  @Test
  void missingDateUsesDocumentedFallbackAndThrottleWorks() {
    var o = create("taipei", "ECPAY");
    var p = response(o);
    p.remove("PaymentDate");
    stub(p);
    assertThat(service.reconcile(manager(), o.id()).detail()).contains("以查核時間入帳");
    var unpaid = create("taipei", "ECPAY");
    p = response(unpaid);
    p.put("TradeStatus", "0");
    stub(p);
    service.reconcile(manager(), unpaid.id());
    assertThatThrownBy(() -> service.reconcile(manager(), unpaid.id()))
        .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(429));
  }

  @Test
  void outOfRangePaymentDateFallsBackWithoutRejectingConfirmedPayment() {
    for (String date :
        List.of("2999/01/01 00:00:00", "1970/01/01 08:00:00", "1969/12/31 23:59:59")) {
      var o = create("taipei", "ECPAY");
      var p = response(o);
      p.put("PaymentDate", date);
      stub(p);
      long before = System.currentTimeMillis();
      var result = service.reconcile(manager(), o.id());
      long after = System.currentTimeMillis();
      assertThat(result.outcome()).as(date).isEqualTo("CONFIRMED");
      assertThat(result.detail()).contains("以查核時間入帳");
      assertThat(orders.paymentSnapshot(o.id()).status()).isEqualTo("PAID");
      assertThat(orders.paymentSnapshot(o.id()).paidAt()).isBetween(before, after);
      assertThat(orders.paymentSnapshot(o.id()).paidAt()).isEqualTo(result.queriedAt());
      assertThat(service.history(manager(), o.id()))
          .singleElement()
          .extracting(Reconciliation.Attempt::detail)
          .isEqualTo(result.detail());
    }
  }

  @Test
  void reconciliationRevenueBelongsToTaipeiPaymentDayNotQueryDay() {
    var o = create("taipei", "ECPAY");
    var p = response(o);
    // Taipei March 1 is still February in UTC: catch both day and month errors.
    p.put("PaymentDate", "2021/03/01 00:30:00");
    stub(p);
    assertThat(service.reconcile(manager(), o.id()).outcome()).isEqualTo("CONFIRMED");
    var march = json.valueToTree(reports.report(manager(), "2021-03", "taipei"));
    assertThat(march.path("revenue").asLong()).isEqualTo(o.total());
    assertThat(march.path("daily").get(0).path("day").asText()).isEqualTo("01");
    assertThat(march.path("daily").get(0).path("revenue").asLong()).isEqualTo(o.total());
    assertThat(march.path("daily").get(0).path("orders").asInt()).isEqualTo(1);
    var february = json.valueToTree(reports.report(manager(), "2021-02", "taipei"));
    assertThat(february.path("revenue").asLong()).isZero();
    var today = LocalDate.now(ZoneId.of("Asia/Taipei"));
    var current =
        json.valueToTree(reports.report(manager(), YearMonth.from(today).toString(), "taipei"));
    assertThat(current.path("daily").get(today.getDayOfMonth() - 1).path("revenue").asLong())
        .isZero();
  }

  @Test
  void pendingScopeAgeAndScheduler() {
    var eligible = create("taipei", "ECPAY");
    var other = create("banqiao", "ECPAY");
    var fresh = create("taipei", "ECPAY");
    var old = create("taipei", "ECPAY");
    var cash = create("taipei", "CASH");
    db.update(
        "update orders set created_at=? where id in (?,?,?)",
        System.currentTimeMillis() - 3600000,
        eligible.id(),
        other.id(),
        cash.id());
    db.update(
        "update orders set created_at=? where id=?",
        System.currentTimeMillis() - 8 * 86400000L,
        old.id());
    assertThat(service.pending(manager()).items())
        .extracting(Reconciliation.Pending::orderId)
        .containsExactly(eligible.id());
    assertThat(service.pending(manager()).truncated()).isFalse();
    when(query.query(anyString()))
        .thenAnswer(inv -> sign(response(orders.paymentSnapshot(inv.getArgument(0)))));
    service.scheduled();
    assertThat(orders.paymentSnapshot(eligible.id()).status()).isEqualTo("PAID");
    assertThat(orders.paymentSnapshot(other.id()).status()).isEqualTo("PAID");
    for (var o : List.of(fresh, old, cash))
      assertThat(orders.paymentSnapshot(o.id()).paidAt()).isNull();
  }

  @Test
  void pendingIsCappedAtTwoHundredAndMarksTruncation() {
    long createdAt = System.currentTimeMillis() - 3600000;
    String accountId = identity.find("customer").id();
    for (int i = 0; i < 201; i++) {
      String id = String.format("P%019d", i);
      db.update(
          "insert into orders(id,branch_id,account_id,status,fulfillment,payment_method,total,note,created_at,idempotency_key,request_hash)"
              + " values(?,?,?,'PENDING_PAYMENT','TAKEAWAY','ECPAY',100,'',?,?,?)",
          id,
          "taipei",
          accountId,
          createdAt + i,
          "pending-" + i,
          "hash-" + i);
    }
    var page = service.pending(manager());
    assertThat(page.items()).hasSize(200);
    assertThat(page.truncated()).isTrue();
  }

  @Test
  void branchSelfPermissionAndCsrfEnforced() throws Exception {
    var o = create("banqiao", "ECPAY");
    for (Actor a : List.of(manager(), identity.find("customer"), identity.find("cashier"))) {
      assertThatThrownBy(() -> service.reconcile(a, o.id()))
          .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(403));
      assertThatThrownBy(() -> service.history(a, o.id()))
          .isInstanceOfSatisfying(Problem.class, e -> assertThat(e.status).isEqualTo(403));
    }
    mvc.perform(get("/api/payments/reconciliation/pending")).andExpect(status().isUnauthorized());
    var session =
        (MockHttpSession)
            mvc.perform(
                    post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                Map.of(
                                    "username",
                                    "manager@coffee.local",
                                    "password",
                                    "CoffeeDemo!2026"))))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession();
    var own = create("taipei", "ECPAY");
    var p = response(own);
    p.put("TradeStatus", "0");
    stub(p);
    mvc.perform(post("/api/payments/reconciliation/" + own.id()).session(session))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/payments/reconciliation/" + o.id()).session(session).with(csrf()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(query);
    mvc.perform(post("/api/payments/reconciliation/" + own.id()).session(session).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome").value("STILL_UNPAID"));
    verify(query, times(1)).query(own.id());
  }

  @Test
  void parserRejectsDuplicateAndMalformedParameters() {
    assertThat(EcpayTradeQuery.parse("Text=%E6%B8%AC%E8%A9%A6&X=a%3Db"))
        .containsEntry("Text", "測試")
        .containsEntry("X", "a=b");
    for (String body : List.of("A=1&A=2", "A=1&a=2", "A=%ZZ", "A"))
      assertThatThrownBy(() -> EcpayTradeQuery.parse(body)).isInstanceOf(Problem.class);
  }

  @Test
  void callbackDuringQueryDoesNotOverwritePaymentTime() {
    var o = create("taipei", "ECPAY");
    var p = sign(response(o));
    long callbackTime = System.currentTimeMillis() - 1000;
    when(query.query(o.id()))
        .thenAnswer(
            inv -> {
              orders.confirmOnline(o.id(), o.total(), p.get("TradeNo"), callbackTime);
              return p;
            });
    assertThat(service.reconcile(manager(), o.id()).outcome()).isEqualTo("CONFIRMED");
    assertThat(orders.paymentSnapshot(o.id()).paidAt()).isEqualTo(callbackTime);
  }

  @Test
  void concurrentManualQueriesAreThrottled() throws Exception {
    var o = create("taipei", "ECPAY");
    var p = response(o);
    p.put("TradeStatus", "0");
    stub(p);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    Actor actor = manager();
    try {
      var work =
          (java.util.concurrent.Callable<Integer>)
              () -> {
                try {
                  service.reconcile(actor, o.id());
                  return 200;
                } catch (Problem e) {
                  return e.status;
                }
              };
      var results = pool.invokeAll(List.of(work, work));
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder(200, 429);
      verify(query, times(1)).query(o.id());
    } finally {
      pool.shutdownNow();
    }
  }
}
