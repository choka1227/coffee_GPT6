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
            branch, "TAKEAWAY", method, "", List.of(new Orders.LineInput("latte", 1, "熱", "無糖"))),
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
    assertThat(service.pending(manager()))
        .extracting(Reconciliation.Pending::orderId)
        .containsExactly(eligible.id());
    when(query.query(anyString()))
        .thenAnswer(inv -> sign(response(orders.paymentSnapshot(inv.getArgument(0)))));
    service.scheduled();
    assertThat(orders.paymentSnapshot(eligible.id()).status()).isEqualTo("PAID");
    assertThat(orders.paymentSnapshot(other.id()).status()).isEqualTo("PAID");
    for (var o : List.of(fresh, old, cash))
      assertThat(orders.paymentSnapshot(o.id()).paidAt()).isNull();
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
    mvc.perform(post("/api/payments/reconciliation/" + o.id()).session(session))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/payments/reconciliation/" + o.id()).session(session).with(csrf()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(query);
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
