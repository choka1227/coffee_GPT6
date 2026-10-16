package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.shared.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:payment-anomaly-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "ecpay.enabled=true",
      "ecpay.environment=stage",
      "ecpay.merchant-id=3002607",
      "ecpay.hash-key=anomaly-test-key",
      "ecpay.hash-iv=anomaly-test-iv",
      "ecpay.reconcile.enabled=true",
      "ecpay.reconcile.interval-ms=86400000",
      "ecpay.expire.enabled=true",
      "ecpay.expire.checkout-window-minutes=10",
      "ecpay.expire.after-minutes=40",
      "ecpay.expire.recheck-after-minutes=10",
      "ecpay.expire.interval-ms=86400000"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class PaymentAnomalyApiTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired Identity identity;
  @Autowired Orders orders;
  @Autowired ObjectMapper json;

  @BeforeEach
  void clean() {
    db.update("delete from payment_anomalies");
    db.update("delete from payment_expiries");
    db.update("delete from payment_reconciliations");
    db.update("delete from payment_events");
    db.update("delete from order_item_promotions");
    db.update("delete from order_discounts");
    db.update("delete from order_item_options");
    db.update("delete from order_items");
    db.update("delete from orders");
  }

  private MockHttpSession session(String account) {
    Actor actor = identity.find(account);
    var session = new MockHttpSession();
    session.setAttribute("ACCOUNT_ID", actor.id());
    session.setAttribute("ACCOUNT_VERSION", identity.sessionVersion(actor.id()));
    return session;
  }

  private Orders.Order order(String branch) {
    return orders.create(
        identity.find("customer"),
        new Orders.Create(
            branch,
            "TAKEAWAY",
            "ECPAY",
            "",
            null,
            List.of(new Orders.LineInput("latte", 1, List.of("temp-hot", "sugar-none"))), null),
        UUID.randomUUID().toString());
  }

  private String anomaly(String branch, long detectedAt) {
    var order = order(branch);
    String id = UUID.randomUUID().toString();
    db.update(
        "insert into payment_anomalies(id,order_id,branch_id,order_total,kind,source,provider_trade_no,trade_amount,detected_at)"
            + " values(?,?,?,?,'PAID_AFTER_EXPIRY','CALLBACK',?,?,?)",
        id,
        order.id(),
        branch,
        order.total(),
        "T" + order.id(),
        order.total(),
        detectedAt);
    return id;
  }

  @Test
  void listScopesUnresolvedAnomaliesAndCapsAtTwoHundred() throws Exception {
    anomaly("taipei", 10);
    anomaly("banqiao", 20);

    mvc.perform(get("/api/payments/anomalies").session(session("hq")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));
    mvc.perform(get("/api/payments/anomalies").session(session("manager")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].branchId").value("taipei"));
    mvc.perform(get("/api/payments/anomalies").session(session("customer")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/payments/anomalies")).andExpect(status().isUnauthorized());

    db.update("update payment_anomalies set resolved_at=1 where branch_id='taipei'");
    mvc.perform(get("/api/payments/anomalies").session(session("hq")))
        .andExpect(jsonPath("$.items.length()").value(1));

    db.update("delete from payment_anomalies");
    for (int i = 0; i < 201; i++) anomaly("taipei", i);
    mvc.perform(get("/api/payments/anomalies").session(session("hq")))
        .andExpect(jsonPath("$.items.length()").value(200))
        .andExpect(jsonPath("$.truncated").value(true));
  }

  @Test
  void resolveValidatesScopeCsrfNoteExistenceAndState() throws Exception {
    String own = anomaly("taipei", 10);
    String other = anomaly("banqiao", 20);
    var manager = session("manager");

    mvc.perform(
            post("/api/payments/anomalies/" + own + "/resolve")
                .session(manager)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"退款完成\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/payments/anomalies/" + other + "/resolve")
                .session(manager)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"退款完成\"}"))
        .andExpect(status().isForbidden());
    assertThat(db.queryForObject("select resolved_at from payment_anomalies where id=?", Long.class, other))
        .isNull();
    mvc.perform(
            post("/api/payments/anomalies/" + own + "/resolve")
                .session(session("customer"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"退款完成\"}"))
        .andExpect(status().isForbidden());
    for (String note : List.of("   ", "甲".repeat(201)))
      mvc.perform(
              post("/api/payments/anomalies/" + own + "/resolve")
                  .session(manager)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(Map.of("note", note))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("請填寫處理說明（200 字以內）"));
    mvc.perform(
            post("/api/payments/anomalies/missing/resolve")
                .session(manager)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"退款完成\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("找不到這筆異常"));

    String response =
        mvc.perform(
                post("/api/payments/anomalies/" + own + "/resolve")
                    .session(manager)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"note\":\"  退款完成  \"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain("anomaly-test-key", "anomaly-test-iv");
    assertThat(
            db.queryForMap(
                "select resolved_by,resolution_note from payment_anomalies where id=?", own))
        .containsEntry("resolved_by", identity.find("manager").id())
        .containsEntry("resolution_note", "退款完成");
    mvc.perform(
            post("/api/payments/anomalies/" + own + "/resolve")
                .session(manager)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"再次退款\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("這筆異常已處理"));
  }

  @Test
  void paymentConfigAddsExpiryWithoutRemovingExistingKeys() throws Exception {
    mvc.perform(get("/api/payments/config").session(session("hq")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.environment").value("stage"))
        .andExpect(jsonPath("$.expire.enabled").value(true))
        .andExpect(jsonPath("$.expire.checkoutWindowMinutes").value(10))
        .andExpect(jsonPath("$.expire.afterMinutes").value(40));
  }
}
