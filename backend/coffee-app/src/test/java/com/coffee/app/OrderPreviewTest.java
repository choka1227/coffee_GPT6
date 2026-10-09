package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffee.catalog.api.Catalog;
import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:order-preview-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "server.servlet.session.cookie.secure=false"
    })
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OrderPreviewTest {
  @Autowired Orders orders;
  @Autowired Catalog catalog;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;
  @LocalServerPort int port;
  final ObjectMapper json = new ObjectMapper();

  @Test
  void customerPreviewReturnsBackendSubtotal() {
    var quote = orders.preview(identity.find("customer"), request(null, 2));

    assertThat(quote.subtotal()).isEqualTo(280);
    assertThat(quote.total()).isEqualTo(280);
    assertThat(quote.items()).singleElement().satisfies(line -> {
      assertThat(line.productId()).isEqualTo("latte");
      assertThat(line.unitPrice()).isEqualTo(140);
      assertThat(line.quantity()).isEqualTo(2);
      assertThat(line.lineTotal()).isEqualTo(280);
    });
  }

  @Test
  void promotionAndCodePreserveIdentitiesWithoutWriting() {
    insertPromotion();
    insertDiscount("PREVIEW-10", 10);
    Map<String, Integer> before = counts();

    var quote = orders.preview(identity.find("customer"), request("PREVIEW-10", 2));

    assertThat(quote.itemDiscountAmount()).isEqualTo(140);
    assertThat(quote.codeDiscountAmount()).isEqualTo(14);
    assertThat(quote.discountAmount())
        .isEqualTo(quote.itemDiscountAmount() + quote.codeDiscountAmount());
    assertThat(quote.total()).isEqualTo(quote.subtotal() - quote.discountAmount());
    assertThat(quote.itemPromotion()).isNotNull();
    assertThat(quote.discount()).isNotNull();
    assertThat(quote.items()).singleElement().extracting(Orders.PreviewLine::discountAmount)
        .isEqualTo(140);
    assertThat(counts()).isEqualTo(before);
    assertThat(redeemed("PREVIEW-10")).isZero();
  }

  @Test
  void realHttpPreviewMatchesTheCreatedOrderAndRequiresAuthentication() throws Exception {
    String previewBody = json.writeValueAsString(Map.of(
        "branchId", "taipei",
        "items", List.of(Map.of(
            "productId", "latte",
            "quantity", 2,
            "optionIds", List.of("temp-hot", "sugar-none")))));

    var anonymous = new BrowserSession();
    var csrf = anonymous.call("GET", "/api/auth/csrf", null);
    anonymous.token = csrf.get("token").asText();
    anonymous.header = csrf.get("headerName").asText();
    assertThat(anonymous.request("POST", "/api/orders/preview", previewBody, Map.of()).statusCode())
        .isEqualTo(401);

    var customer = new BrowserSession();
    customer.login("customer@coffee.local", "CoffeeDemo!2026");
    JsonNode customerQuote = customer.call("POST", "/api/orders/preview", previewBody);
    assertThat(customerQuote.get("subtotal").asInt()).isEqualTo(280);

    var cashier = new BrowserSession();
    cashier.login("cashier@coffee.local", "CoffeeDemo!2026");
    JsonNode quote = cashier.call("POST", "/api/orders/preview", previewBody);
    String createBody = json.writeValueAsString(Map.of(
        "branchId", "taipei",
        "fulfillment", "TAKEAWAY",
        "paymentMethod", "CASH",
        "note", "",
        "items", List.of(Map.of(
            "productId", "latte",
            "quantity", 2,
            "optionIds", List.of("temp-hot", "sugar-none")))));
    var created = cashier.request(
        "POST",
        "/api/orders",
        createBody,
        Map.of("Idempotency-Key", UUID.randomUUID().toString()));

    assertThat(created.statusCode()).isEqualTo(200);
    assertThat(quote.get("total").asInt())
        .isEqualTo(json.readTree(created.body()).get("total").asInt());
  }

  @Test
  void previewDoesNotReserveLimitedStock() {
    Actor cashier = identity.find("cashier");
    catalog.setStock(cashier, "taipei", "latte", 2);

    for (int i = 0; i < 10; i++) orders.preview(cashier, request(null, 2));

    assertThat(remaining()).isEqualTo(2);
    Orders.Order created = orders.create(
        cashier,
        new Orders.Create(
            "taipei", "TAKEAWAY", "CASH", "", null,
            List.of(line(2))),
        UUID.randomUUID().toString());
    assertThat(created.total()).isEqualTo(280);
    assertThat(remaining()).isZero();
  }

  @Test
  void previewEnforcesPermissionsAndReturnsTheSameSoldOutProblemAsCreate() {
    Actor cashier = identity.find("cashier");
    assertThatThrownBy(() -> orders.preview(cashier, new Orders.PreviewRequest(
            "banqiao", null, List.of(line(1)))))
        .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status).isEqualTo(403));
    Actor noPermission =
        new Actor("none", "none", "無權限", "CUSTOM", "GLOBAL", null, Set.of());
    assertThatThrownBy(() -> orders.preview(noPermission, request(null, 1)))
        .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status).isEqualTo(403));

    catalog.setStock(cashier, "taipei", "latte", 0);
    Problem preview = problem(() -> orders.preview(cashier, request(null, 1)));
    Problem create = problem(() -> orders.create(
        cashier,
        new Orders.Create("taipei", "TAKEAWAY", "CASH", "", null, List.of(line(1))),
        UUID.randomUUID().toString()));

    assertThat(preview.status).isEqualTo(400);
    assertThat(preview.status).isEqualTo(create.status);
    assertThat(preview).hasMessage(create.getMessage());
  }

  @Test
  void sameAccountCanPreviewSixtyTimesAndTheNextAttemptIsRateLimited() {
    Actor customer = customer("rate-limited");

    for (int i = 0; i < 60; i++) orders.preview(customer, request(null, 1));

    assertThatThrownBy(() -> orders.preview(customer, request(null, 1)))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(429);
              assertThat(problem).hasMessage("試算過於頻繁，請稍後再試");
            });
  }

  @Test
  void previewLimitsAreIndependentForDifferentAccounts() {
    Actor first = customer("first");
    Actor second = customer("second");

    for (int i = 0; i < 60; i++) orders.preview(first, request(null, 1));

    assertThatThrownBy(() -> orders.preview(first, request(null, 1)))
        .isInstanceOf(Problem.class);
    assertThat(orders.preview(second, request(null, 1)).total()).isEqualTo(140);
  }

  @Test
  void forbiddenCrossBranchPreviewsDoNotConsumeTheLimit() {
    Actor cashier = identity.find("cashier");
    var forbidden = new Orders.PreviewRequest("banqiao", null, List.of(line(1)));

    for (int i = 0; i < 100; i++) {
      assertThatThrownBy(() -> orders.preview(cashier, forbidden))
          .isInstanceOfSatisfying(
              Problem.class, problem -> assertThat(problem.status).isEqualTo(403));
    }

    assertThat(orders.preview(cashier, request(null, 1)).total()).isEqualTo(140);
  }

  @Test
  void malformedPreviewsDoNotConsumeTheLimit() {
    Actor customer = customer("malformed");
    var empty = new Orders.PreviewRequest("taipei", null, List.of());

    for (int i = 0; i < 100; i++) {
      assertThatThrownBy(() -> orders.preview(customer, empty))
          .isInstanceOfSatisfying(
              Problem.class, problem -> assertThat(problem.status).isEqualTo(400));
    }

    assertThat(orders.preview(customer, request(null, 1)).total()).isEqualTo(140);
  }

  @Test
  void creatingOrdersIsNotAffectedByAConsumedPreviewLimit() {
    Actor cashier = identity.find("cashier");
    for (int i = 0; i < 60; i++) orders.preview(cashier, request(null, 1));
    assertThatThrownBy(() -> orders.preview(cashier, request(null, 1)))
        .isInstanceOf(Problem.class);

    for (int i = 0; i < 3; i++) {
      Orders.Order created = orders.create(
          cashier,
          new Orders.Create(
              "taipei", "TAKEAWAY", "CASH", "", null, List.of(line(1))),
          UUID.randomUUID().toString());
      assertThat(created.total()).isEqualTo(140);
    }
  }

  private Actor customer(String id) {
    return new Actor(id, id, id, "CUSTOMER", "SELF", null, Set.of("ORDER_CREATE"));
  }

  private Orders.PreviewRequest request(String code, int quantity) {
    return new Orders.PreviewRequest("taipei", code, List.of(line(quantity)));
  }

  private Orders.LineInput line(int quantity) {
    return new Orders.LineInput("latte", quantity, List.of("temp-hot", "sugar-none"));
  }

  private Map<String, Integer> counts() {
    return Map.of(
        "orders", count("orders"),
        "order_items", count("order_items"),
        "order_discounts", count("order_discounts"),
        "audit_log", count("audit_log"));
  }

  private int count(String table) {
    return db.queryForObject("select count(*) from " + table, Integer.class);
  }

  private int redeemed(String code) {
    return db.queryForObject(
        "select redeemed_count from discounts where code=?", Integer.class, code);
  }

  private int remaining() {
    int today = Integer.parseInt(
        LocalDate.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.BASIC_ISO_DATE));
    return db.queryForObject(
        "select remaining from branch_product_stock"
            + " where branch_id='taipei' and product_id='latte' and on_date=?",
        Integer.class,
        today);
  }

  private void insertPromotion() {
    db.update(
        "insert into item_promotions(id,name,kind,percent,nth,target_kind,product_id,category,"
            + "branch_id,starts_at,ends_at,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        "preview-bogo", "買一送一", "NTH_PERCENT", 100, 2, "PRODUCT", "latte", null, null,
        null, null, true, 1_000L, 1_000L);
  }

  private void insertDiscount(String code, int percent) {
    db.update(
        "insert into discounts(id,code,name,kind,percent,amount,min_subtotal,branch_id,starts_at,"
            + "ends_at,max_redemptions,redeemed_count,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), code, code, "PERCENT", percent, 0, 0, null, null, null,
        null, 0, true, 1_000L, 1_000L);
  }

  private Problem problem(Runnable action) {
    try {
      action.run();
      throw new AssertionError("預期操作失敗");
    } catch (Problem problem) {
      return problem;
    }
  }

  class BrowserSession {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;
    String header;

    HttpResponse<String> request(String method, String path, String body, Map<String, String> extra)
        throws Exception {
      var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
      if (body != null) builder.header("Content-Type", "application/json");
      if (!method.equals("GET") && token != null) builder.header(header, token);
      extra.forEach(builder::header);
      return client.send(
          builder
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode call(String method, String path, String body) throws Exception {
      var response = request(method, path, body, Map.of());
      assertThat(response.statusCode())
          .withFailMessage("%s %s: %s", method, path, response.body())
          .isEqualTo(200);
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
}
