package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.coffee.payments.api.CheckMac;
import com.coffee.shared.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:coffee-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "ecpay.enabled=true",
      "ecpay.environment=stage",
      "ecpay.merchant-id=3002607",
      "ecpay.hash-key=pwFHCqoQZGmho4w6",
      "ecpay.hash-iv=EkRm7iFT261dpevs",
      "app.public-url=https://coffee.example.test"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class CoffeeIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;

  MockHttpSession login(String role) throws Exception {
    return (MockHttpSession)
        mvc.perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "username",
                                role + "@coffee.local",
                                "password",
                                "CoffeeDemo!2026"))))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession();
  }

  String body(String branch, String payment, int quantity) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "branchId",
            branch,
            "fulfillment",
            "TAKEAWAY",
            "paymentMethod",
            payment,
            "note",
            "測試訂單",
            "total",
            1,
            "items",
            List.of(
                Map.of(
                    "productId",
                    "latte",
                    "quantity",
                    quantity,
                    "temperature",
                    "熱",
                    "sugar",
                    "無糖",
                    "unitPrice",
                    1))));
  }

  JsonNode create(MockHttpSession session, String branch, String payment, int quantity, String key)
      throws Exception {
    return json.readTree(
        mvc.perform(
                post("/api/orders")
                    .session(session)
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(branch, payment, quantity)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  JsonNode create(MockHttpSession session, String branch, String payment) throws Exception {
    return create(session, branch, payment, 2, UUID.randomUUID().toString());
  }

  @Test
  void authenticationAndCsrfAreRequired() throws Exception {
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty());
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"customer@coffee.local\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void serverCalculatesPricesAndIdempotencyPreventsDuplicates() throws Exception {
    var customer = login("customer");
    String key = UUID.randomUUID().toString();
    var first = create(customer, "taipei", "CASH", 2, key);
    var second = create(customer, "taipei", "CASH", 2, key);
    assertThat(first.get("total").asInt()).isEqualTo(280);
    assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
    assertThat(
            db.queryForObject(
                "select count(*) from orders where idempotency_key=?", Integer.class, key))
        .isEqualTo(1);
    mvc.perform(
            post("/api/orders")
                .session(customer)
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("taipei", "CASH", 3)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void customerCannotReadAdminDataOrSettleCash() throws Exception {
    var c = login("customer");
    var o = create(c, "taipei", "CASH");
    for (String path :
        List.of(
            "/api/admin/accounts",
            "/api/admin/roles",
            "/api/menu?manage=true",
            "/api/reports?month=" + YearMonth.now(ZoneId.of("Asia/Taipei"))))
      mvc.perform(get(path).session(c)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/orders/" + o.get("id").asText() + "/cash")
                .session(c)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tendered\":500}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/menu").session(c)).andExpect(jsonPath("$[0].cost").value(0));
  }

  @Test
  void managersCannotReadOrChargeAnotherBranch() throws Exception {
    var c = login("customer");
    var o = create(c, "banqiao", "CASH");
    var manager = login("manager");
    String id = o.get("id").asText();
    mvc.perform(get("/api/orders/" + id).session(manager)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/orders/" + id + "/cash")
                .session(manager)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tendered\":500}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/reports?month="
                    + YearMonth.now(ZoneId.of("Asia/Taipei"))
                    + "&branchId=banqiao")
                .session(manager))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/orders")
                .session(manager)
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("banqiao", "CASH", 1)))
        .andExpect(status().isForbidden());
  }

  @Test
  void cashSettlementIsAtomicIdempotentAndUpdatesRevenue() throws Exception {
    var cashier = login("cashier");
    var o = create(cashier, "taipei", "CASH");
    String id = o.get("id").asText();
    mvc.perform(
            post("/api/orders/" + id + "/cash")
                .session(cashier)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tendered\":100}"))
        .andExpect(status().isBadRequest());
    var manager = login("manager");
    String path = "/api/reports?month=" + YearMonth.now(ZoneId.of("Asia/Taipei"));
    long before =
        json.readTree(
                mvc.perform(get(path).session(manager))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("revenue")
            .asLong();
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/orders/" + id + "/cash")
                  .session(cashier)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"tendered\":500}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.changeAmount").value(220))
          .andExpect(jsonPath("$.status").value("PAID"));
    long after =
        json.readTree(
                mvc.perform(get(path).session(manager))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("revenue")
            .asLong();
    assertThat(after - before).isEqualTo(280);
  }

  @Test
  void orderStateMachineRejectsSkippingPreparationAndPaidCancellation() throws Exception {
    var c = login("cashier");
    var o = create(c, "taipei", "CASH");
    String id = o.get("id").asText();
    mvc.perform(
            patch("/api/orders/" + id + "/status")
                .session(c)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"COMPLETED\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/orders/" + id + "/cash")
                .session(c)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tendered\":280}"))
        .andExpect(status().isOk());
    mvc.perform(
            patch("/api/orders/" + id + "/status")
                .session(c)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CANCELLED\"}"))
        .andExpect(status().isBadRequest());
    for (String state : List.of("PREPARING", "READY", "COMPLETED"))
      mvc.perform(
              patch("/api/orders/" + id + "/status")
                  .session(c)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(Map.of("status", state))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value(state));
  }

  @Test
  void menuChangesDoNotRewriteHistoricalPrices() throws Exception {
    var c = login("customer");
    var o = create(c, "taipei", "CASH");
    db.update("update products set price=199 where id='latte'");
    mvc.perform(get("/api/orders/" + o.get("id").asText()).session(c))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(280))
        .andExpect(jsonPath("$.items[0].unitPrice").value(140));
  }

  Map<String, String> notification(String id, String amount, String simulate) {
    var p = new HashMap<String, String>();
    p.put("MerchantID", "3002607");
    p.put("MerchantTradeNo", id);
    p.put("TradeNo", "ECPAY" + id);
    p.put("TradeAmt", amount);
    p.put("RtnCode", "1");
    p.put("SimulatePaid", simulate);
    p.put("CheckMacValue", CheckMac.sign(p, "pwFHCqoQZGmho4w6", "EkRm7iFT261dpevs"));
    return p;
  }

  ResultActions callback(Map<String, String> p) throws Exception {
    var request =
        post("/api/payments/ecpay/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
    p.forEach(request::param);
    return mvc.perform(request);
  }

  @Test
  void ecpayRejectsTamperingMismatchAndSimulationThenHandlesReplay() throws Exception {
    var c = login("customer");
    var o = create(c, "taipei", "ECPAY");
    String id = o.get("id").asText();
    assertThat(id.length()).isEqualTo(20);
    mvc.perform(post("/api/payments/ecpay/" + id).session(c).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fields.TotalAmount").value("280"));
    var tamper = notification(id, "280", "0");
    tamper.put("TradeAmt", "1");
    callback(tamper).andExpect(status().isBadRequest());
    callback(notification(id, "281", "0")).andExpect(status().isBadRequest());
    callback(notification(id, "280", "1"))
        .andExpect(status().isOk())
        .andExpect(content().string("1|OK"));
    assertThat(db.queryForObject("select status from orders where id=?", String.class, id))
        .isEqualTo("PENDING_PAYMENT");
    for (int i = 0; i < 2; i++)
      callback(notification(id, "280", "0"))
          .andExpect(status().isOk())
          .andExpect(content().string("1|OK"));
    assertThat(db.queryForObject("select status from orders where id=?", String.class, id))
        .isEqualTo("PAID");
  }

  @Test
  void disabledAccountsAndPasswordResetsRevokeSessions() throws Exception {
    var manager = login("manager");
    db.update("update accounts set active=false where id='manager'");
    mvc.perform(get("/api/auth/me").session(manager)).andExpect(status().isUnauthorized());
    var c = login("customer");
    db.update("update accounts set session_version=session_version+1 where id='customer'");
    mvc.perform(get("/api/auth/me").session(c)).andExpect(status().isUnauthorized());
  }

  @Test
  void roleRevocationAppliesToExistingSessions() throws Exception {
    var manager = login("manager");
    db.update(
        "delete from role_permissions where role_code='MANAGER' and permission='REPORT_STORE'");
    mvc.perform(
            get("/api/reports?month=" + YearMonth.now(ZoneId.of("Asia/Taipei"))).session(manager))
        .andExpect(status().isForbidden());
  }
}
