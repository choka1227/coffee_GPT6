package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.orders.api.Orders;
import com.coffee.orders.api.Orders.InvoiceRequest;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:g02-orders;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "server.servlet.session.cookie.secure=false", "orders.invoice-required=false"
})
@ActiveProfiles("dev")
class OrderInvoiceRequestTest {
  @Autowired Orders orders;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;
  private final Actor cashier = new Actor("cashier", "cashier", "收銀員", "CASHIER", "BRANCH",
      "taipei", Set.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE", "PAYMENT_RECONCILE"));

  @BeforeEach
  void resetRequirement() {
    required(false);
  }

  private void required(boolean value) {
    ReflectionTestUtils.setField(AopTestUtils.getUltimateTargetObject(orders), "invoiceRequired", value);
  }

  private Orders.Create request(InvoiceRequest invoice) {
    return new Orders.Create("taipei", "TAKEAWAY", "CASH", "", null,
        List.of(new Orders.LineInput("latte", 1, List.of("temp-hot", "sugar-none"))), invoice);
  }

  private Orders.Order create(InvoiceRequest invoice) {
    return orders.create(cashier, request(invoice), UUID.randomUUID().toString());
  }

  @Test
  void legacyOrdersAndFingerprintsRemainCompatible() throws Exception {
    String key = UUID.randomUUID().toString();
    Orders.Create request = request(null);
    var order = orders.create(cashier, request, key);
    assertThat(order.invoice()).isNull();
    var row = db.queryForMap("select invoice_type,invoice_email,invoice_carrier,invoice_love_code from orders where id=?", order.id());
    assertThat(row.values()).containsOnlyNulls();
    // Use the actual pre-G02 record representation, including normalized option order.
    String oldRequest = "Create[branchId=taipei, fulfillment=TAKEAWAY, paymentMethod=CASH, note=, discountCode=null, items=[LineInput[productId=latte, quantity=1, optionIds=[sugar-none, temp-hot]]]]";
    String oldHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(oldRequest.getBytes(StandardCharsets.UTF_8)));
    assertThat(db.queryForObject("select request_hash from orders where id=?", String.class, order.id()))
        .isEqualTo(oldHash);
    assertThat(orders.create(cashier, request, key).id()).isEqualTo(order.id());
  }

  static Stream<Arguments> invalidInvoices() {
    return Stream.of(
        Arguments.of(null, "請選擇發票開立方式"),
        Arguments.of(new InvoiceRequest(null, "a@b.co", null, null), "發票開立方式不正確"),
        Arguments.of(new InvoiceRequest("PAPER", "a@b.co", null, null), "發票開立方式不正確"),
        Arguments.of(new InvoiceRequest("CLOUD", null, null, null), "請輸入正確的發票通知 email"),
        Arguments.of(new InvoiceRequest("CLOUD", " ", null, null), "請輸入正確的發票通知 email"),
        Arguments.of(new InvoiceRequest("CLOUD", "bad@address", null, null), "請輸入正確的發票通知 email"),
        Arguments.of(new InvoiceRequest("CLOUD", "a b@c.co", null, null), "請輸入正確的發票通知 email"),
        Arguments.of(new InvoiceRequest("CLOUD", "x".repeat(96) + "@a.co", null, null), "請輸入正確的發票通知 email"),
        Arguments.of(new InvoiceRequest("MOBILE", "a@b.co", null, null), "手機條碼格式不正確"),
        Arguments.of(new InvoiceRequest("MOBILE", "a@b.co", "/ABC123", null), "手機條碼格式不正確"),
        Arguments.of(new InvoiceRequest("MOBILE", "a@b.co", "/ABC12345", null), "手機條碼格式不正確"),
        Arguments.of(new InvoiceRequest("MOBILE", "a@b.co", "/ABC_123", null), "手機條碼格式不正確"),
        Arguments.of(new InvoiceRequest("DONATE", "a@b.co", null, null), "捐贈碼格式不正確"),
        Arguments.of(new InvoiceRequest("DONATE", "a@b.co", null, "12"), "捐贈碼格式不正確"),
        Arguments.of(new InvoiceRequest("DONATE", "a@b.co", null, "12345678"), "捐贈碼格式不正確"),
        Arguments.of(new InvoiceRequest("DONATE", "a@b.co", null, "12A"), "捐贈碼格式不正確"));
  }

  @ParameterizedTest
  @MethodSource("invalidInvoices")
  void rejectsEveryInvalidInvoice(InvoiceRequest invoice, String message) {
    required(true);
    assertThatThrownBy(() -> create(invoice)).isInstanceOfSatisfying(Problem.class, p -> {
      assertThat(p.status).isEqualTo(400);
      assertThat(p.getMessage()).isEqualTo(message);
    });
  }

  static Stream<Arguments> validInvoices() {
    return Stream.of(
        Arguments.of(new InvoiceRequest("CLOUD", " a@b.co ", "ignored", "ignored"),
            new InvoiceRequest("CLOUD", "a@b.co", null, null)),
        Arguments.of(new InvoiceRequest("MOBILE", "a@b.co", " /ab1.+-2 ", "ignored"),
            new InvoiceRequest("MOBILE", "a@b.co", "/AB1.+-2", null)),
        Arguments.of(new InvoiceRequest("DONATE", "a@b.co", "ignored", " 001 "),
            new InvoiceRequest("DONATE", "a@b.co", null, "001")),
        Arguments.of(new InvoiceRequest("DONATE", "x".repeat(95) + "@a.co", null, "1234567"),
            new InvoiceRequest("DONATE", "x".repeat(95) + "@a.co", null, "1234567")));
  }

  @ParameterizedTest
  @MethodSource("validInvoices")
  void persistsNormalizedInvoiceInAllOrderProjections(InvoiceRequest input, InvoiceRequest expected) {
    required(true);
    var order = create(input);
    assertThat(order.invoice()).isEqualTo(expected);
    assertThat(orders.get(cashier, order.id()).invoice()).isEqualTo(expected);
    assertThat(orders.paymentSnapshot(order.id()).invoice()).isEqualTo(expected);
    assertThat(orders.page(cashier, new Orders.Query(null, null, null, null, order.id(), null, 10))
        .items()).singleElement().extracting(Orders.Order::invoice).isEqualTo(expected);
    var row = db.queryForMap("select invoice_type,invoice_email,invoice_carrier,invoice_love_code from orders where id=?", order.id());
    assertThat(row.get("invoice_type")).isEqualTo(expected.type());
    assertThat(row.get("invoice_email")).isEqualTo(expected.email());
    assertThat(row.get("invoice_carrier")).isEqualTo(expected.carrier());
    assertThat(row.get("invoice_love_code")).isEqualTo(expected.loveCode());
  }

  @Test
  void invoiceIsPartOfIdempotencyFingerprint() {
    var invoice = new InvoiceRequest("CLOUD", "first@example.test", null, null);
    String key = UUID.randomUUID().toString();
    var first = orders.create(cashier, request(invoice), key);
    assertThat(orders.create(cashier, request(invoice), key).id()).isEqualTo(first.id());
    assertThatThrownBy(() -> orders.create(cashier,
        request(new InvoiceRequest("CLOUD", "other@example.test", null, null)), key))
        .isInstanceOfSatisfying(Problem.class, p -> assertThat(p.status).isEqualTo(409));
  }

  @Test
  void paidCandidateCursorUsesTimeThenIdAndInclusiveUpperBound() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) ids.add(create(new InvoiceRequest("CLOUD", "a@b.co", null, null)).id());
    ids.sort(String::compareTo);
    for (int i = 0; i < 3; i++)
      db.update("update orders set paid_at=1000,status=? where id=?",
          List.of("PAID", "PREPARING", "COMPLETED").get(i), ids.get(i));
    db.update("update orders set paid_at=2000,status='READY' where id=?", ids.get(3));
    db.update("update orders set paid_at=2001,status='PAID' where id=?", ids.get(4));
    var pending = create(null);
    var cancelled = create(null);
    orders.transition(cashier, cancelled.id(), "CANCELLED");
    var first = orders.invoiceCandidates(999, "", 2000, 2);
    assertThat(first).extracting(Orders.InvoiceCandidate::orderId).containsExactly(ids.get(0), ids.get(1));
    var second = orders.invoiceCandidates(first.get(1).paidAt(), first.get(1).orderId(), 2000, 2);
    assertThat(second).extracting(Orders.InvoiceCandidate::orderId).containsExactly(ids.get(2), ids.get(3));
    assertThat(first.get(0).invoice()).isEqualTo(new InvoiceRequest("CLOUD", "a@b.co", null, null));
    assertThat(orders.invoiceCandidates(2000, ids.get(3), 2000, 10)).isEmpty();
    assertThat(orders.invoiceCandidate(ids.get(3))).hasValueSatisfying(c -> {
      assertThat(c.total()).isEqualTo(140);
      assertThat(c.branchId()).isEqualTo("taipei");
    });
    assertThat(orders.invoiceCandidate(pending.id())).isEmpty();
    assertThat(orders.invoiceCandidate(cancelled.id())).isEmpty();
    assertThat(orders.invoiceCandidate("missing")).isEmpty();
  }

  @Test
  void migrationCreatesNamedConstraintsAndIndexesWithoutInitializingCursor() {
    assertThat(db.queryForObject("select count(*) from invoice_cursors", Integer.class)).isZero();
    assertThat(db.queryForObject("select count(*) from invoices", Integer.class)).isZero();
    var constraints = db.queryForList("select constraint_name from information_schema.table_constraints where table_name in ('orders','invoices')", String.class);
    assertThat(constraints).contains("ck_orders_invoice_type", "ck_invoices_amount",
        "ck_invoices_attempts", "ck_invoices_status", "ck_invoices_issued", "ck_invoices_claim",
        "ck_invoices_inflight", "uq_invoices_invoice_no");
    assertThat(db.queryForList("select index_name from information_schema.indexes where table_name='invoices'", String.class))
        .contains("idx_invoices_status", "idx_invoices_branch_status");
  }

  @Test
  void oldHttpPayloadWorksWhileRequiredInvoiceReturnsExact400() throws Exception {
    HttpClient browser = HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    var csrf = json.readTree(send(browser, "GET", "/api/auth/csrf", null, Map.of()).body());
    var login = send(browser, "POST", "/api/auth/login",
        "{\"username\":\"cashier@coffee.local\",\"password\":\"CoffeeDemo!2026\"}",
        Map.of(csrf.get("headerName").asText(), csrf.get("token").asText()));
    assertThat(login.statusCode()).isEqualTo(200);
    csrf = json.readTree(send(browser, "GET", "/api/auth/csrf", null, Map.of()).body());
    var headers = Map.of(csrf.get("headerName").asText(), csrf.get("token").asText(),
        "Idempotency-Key", UUID.randomUUID().toString());
    String body = "{\"branchId\":\"taipei\",\"fulfillment\":\"TAKEAWAY\",\"paymentMethod\":\"CASH\",\"note\":\"\",\"items\":[{\"productId\":\"latte\",\"quantity\":1,\"optionIds\":[\"temp-hot\",\"sugar-none\"]}]}";
    var old = send(browser, "POST", "/api/orders", body, headers);
    assertThat(old.statusCode()).isEqualTo(200);
    assertThat(json.readTree(old.body()).get("invoice").isNull()).isTrue();
    required(true);
    var missing = send(browser, "POST", "/api/orders", body, headers);
    assertThat(missing.statusCode()).isEqualTo(400);
    assertThat(json.readTree(missing.body()).get("message").asText()).isEqualTo("請選擇發票開立方式");
  }

  private HttpResponse<String> send(HttpClient browser, String method, String path, String body,
      Map<String, String> headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    headers.forEach(builder::header);
    if (body != null) builder.header("Content-Type", "application/json");
    return browser.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
}
