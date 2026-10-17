package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import static com.coffee.app.ecpay.FakeEinvoiceServer.Scenario.*;

import com.coffee.app.ecpay.FakeEinvoiceServer;
import com.coffee.orders.api.Orders;
import com.coffee.payments.internal.EcpayInvoiceClient;
import com.coffee.payments.internal.EcpayInvoiceClient.Ok;
import com.coffee.payments.internal.InvoiceService;
import com.coffee.shared.Actor;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:g03-scheduler;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "ecpay.invoice.enabled=true", "orders.invoice-required=true",
    "ecpay.invoice.environment=stage", "ecpay.invoice.merchant-id=fake-merchant",
    "ecpay.invoice.hash-key=TestInvoiceKey12", "ecpay.invoice.hash-iv=TestInvoiceIv123",
    "ecpay.invoice.timeout-ms=1000", "ecpay.invoice.interval-ms=3600000",
    "ecpay.invoice.lag-seconds=120", "ecpay.invoice.max-attempts=3", "ecpay.invoice.batch-size=2"
})
@ActiveProfiles("dev")
@ExtendWith(OutputCaptureExtension.class)
class InvoiceRefundSchedulerTest {
  private static final String KEY = "TestInvoiceKey12", IV = "TestInvoiceIv123";
  private static final String EMAIL = "refund-private@example.test";
  private static final FakeEinvoiceServer fake = startFake();
  @Autowired Orders orders;
  @Autowired InvoiceService service;
  @Autowired EcpayInvoiceClient client;
  @Autowired JdbcTemplate db;
  private final Actor staff = new Actor("cashier", "cashier", "收銀員", "CASHIER", "BRANCH",
      "taipei", Set.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE", "PAYMENT_RECONCILE"));
  private final Actor manager = new Actor("manager", "manager", "店長", "MANAGER", "BRANCH",
      "taipei", Set.of("ORDER_REFUND", "ORDER_MANAGE"));

  private static FakeEinvoiceServer startFake() {
    try { return new FakeEinvoiceServer(KEY, IV); }
    catch (Exception failure) { throw new IllegalStateException("測試伺服器啟動失敗"); }
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("ecpay.invoice.base-url", fake::baseUrl);
  }
  @BeforeEach void reset() {
    db.update("delete from invoice_reversals");
    db.update("delete from invoices");
    db.update("delete from invoice_cursors");
    db.update("update orders set refunded_at=null,refunded_by=null,refund_reason=null,refund_provider_note=null,refund_cash_session_id=null");
    db.update("update orders set paid_at=null");
    fake.reset();
    // Initialize at an explicit historic cutoff so current test refunds enter the scan.
    service.runOnce(System.currentTimeMillis() - 600000);
  }
  @AfterEach void cleanLogs(CapturedOutput output) {
    assertThat(output.getAll()).doesNotContain(KEY, IV, EMAIL, "fake-merchant", "/ABC1234", "7654321");
    assertThat(output.getAll().lines().noneMatch(line -> line.startsWith("\tat "))).isTrue();
  }
  @AfterAll static void close() { fake.close(); }

  private String paid() {
    var order = orders.create(staff, new Orders.Create("taipei", "TAKEAWAY", "CASH", "", null,
        List.of(new Orders.LineInput("latte", 1, List.of("temp-hot", "sugar-none"))),
        new Orders.InvoiceRequest("CLOUD", EMAIL, null, null)), UUID.randomUUID().toString());
    orders.cash(staff, order.id(), order.total());
    return order.id();
  }
  private String pending() {
    String id = paid();
    db.update("insert into invoices(order_id,branch_id,amount,status,created_at,updated_at) "
        + "select id,branch_id,total,'PENDING',0,0 from orders where id=?", id);
    return id;
  }
  private String issued() { String id = pending(); service.issueOne(id); return id; }
  private void refund(String id) { orders.refund(manager, id, new Orders.RefundRequest("測試整筆退款", null)); }
  private void tick() { service.runOnce(System.currentTimeMillis() + 121000); }
  private Map<String, Object> invoice(String id) { return db.queryForMap("select * from invoices where order_id=?", id); }
  private Map<String, Object> reversal(String id) { return db.queryForMap("select * from invoice_reversals where order_id=?", id); }
  private long count(String op) { return fake.received().stream().filter(r -> r.path().equals("/B2CInvoice/" + op)).count(); }
  private void expireIssue(String id) { db.update("update invoices set inflight_until=? where order_id=?", System.currentTimeMillis() - 1, id); }
  private void expireReverse(String id) { db.update("update invoice_reversals set inflight_until=? where order_id=?", System.currentTimeMillis() - 1, id); }
  private void expiredDate(String id) { db.update("update invoices set invoice_date='2026-01-01 12:00:00' where order_id=?", id); }
  private void done(String id, String kind) {
    assertThat(reversal(id)).containsEntry("status", "DONE").containsEntry("kind", kind)
        .containsEntry("uncertain", false).containsEntry("inflight_until", null)
        .containsEntry("claim_token", null).containsEntry("claimed_until", null);
    assertThat(reversal(id).get("done_at")).isNotNull();
    var query = (Ok) client.getIssueByRelateNumber(id);
    if (kind.equals("VOID")) assertThat(query.data().get("IIS_Invalid_Status")).isEqualTo("1");
    else if (kind.equals("ALLOWANCE")) assertThat(query.data().get("IIS_Remain_Allowance_Amt")).isEqualTo(0);
  }
  private void await(CountDownLatch latch) {
    try { assertThat(latch.await(8, TimeUnit.SECONDS)).isTrue(); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
  }

  @Test void refundCursorBoundaryAndLagAreInclusiveAndDurable() {
    db.update("delete from invoice_cursors");
    long t0 = System.currentTimeMillis(), c = t0 - 120000;
    service.runOnce(t0);
    assertThat(db.queryForMap("select * from invoice_cursors where name='REFUNDED'"))
        .containsEntry("at_ms", c).containsEntry("order_id", "");
    String[] ids = {paid(), paid(), paid(), paid()};
    long[] times = {c - 1, c, t0 - 1000, t0 + 1000};
    for (int n = 0; n < ids.length; n++) {
      refund(ids[n]);
      db.update("update orders set refunded_at=?,paid_at=0 where id=?", times[n], ids[n]);
    }
    service.runOnce(t0);
    assertThat(invoice(ids[1])).containsEntry("status", "SKIPPED");
    assertThat(db.queryForObject("select count(*) from invoices", Integer.class)).isEqualTo(1);
    service.runOnce(t0 + 121000);
    assertThat(invoice(ids[2])).containsEntry("status", "SKIPPED");
    assertThat(invoice(ids[3])).containsEntry("status", "SKIPPED");
    assertThat(db.queryForObject("select count(*) from invoices where order_id=?", Integer.class, ids[0])).isZero();
    assertThat(count("Issue")).isZero();
  }

  @Test void issuedRefundVoidsAndReportsAggregate(CapturedOutput output) {
    String id = issued(); refund(id); tick(); done(id, "VOID");
    assertThat(count("Invalid")).isEqualTo(1); assertThat(count("Allowance")).isZero();
    assertThat(output.getAll()).contains("作廢 1、折讓 0、作廢折讓失敗 0");
  }
  @Test void expiredInvoiceUsesFullAllowanceWithoutVoid() {
    String id = issued(); expiredDate(id); refund(id); tick(); done(id, "ALLOWANCE");
    assertThat(reversal(id).get("allowance_no")).isNotNull();
    assertThat(count("Invalid")).isZero(); assertThat(count("Allowance")).isEqualTo(1);
    var request = fake.received().stream().filter(r -> r.path().endsWith("/Allowance")).findFirst().orElseThrow();
    int amount = ((Number) invoice(id).get("amount")).intValue();
    assertThat(request.data().path("AllowanceAmount").asInt()).isEqualTo(amount);
    assertThat(request.data().path("Items").get(0).path("ItemPrice").asInt()).isEqualTo(amount);
    assertThat(request.data().path("Items").get(0).path("ItemAmount").asInt()).isEqualTo(amount);
  }
  @Test void refusedVoidFallsBackOnceInSameRound() {
    String id = issued(); refund(id); fake.respond("Invalid", VOID_PAST_DEADLINE);
    tick(); done(id, "ALLOWANCE");
    assertThat(count("Invalid")).isEqualTo(1); assertThat(count("Allowance")).isEqualTo(1);
  }
  @ParameterizedTest @ValueSource(strings = {"Invalid", "Allowance"})
  void reversalSucceedThenTimeoutQueriesInsteadOfResending(String op) {
    String id = issued(); if (op.equals("Allowance")) expiredDate(id);
    refund(id); fake.respond(op, SUCCEED_THEN_TIMEOUT); tick();
    assertThat(reversal(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true);
    assertThat(reversal(id).get("inflight_until")).isNotNull();
    expireReverse(id); tick(); done(id, op.equals("Invalid") ? "VOID" : "ALLOWANCE");
    assertThat(count(op)).isEqualTo(1);
    assertThat(count(op.equals("Invalid") ? "Allowance" : "Invalid")).isZero();
  }
  @Test void refundBeforeEnqueueSkipsWithoutProviderRequest() {
    String id = paid(); refund(id); tick(); tick();
    assertThat(invoice(id)).containsEntry("status", "SKIPPED").containsEntry("uncertain", false);
    assertThat(fake.received()).isEmpty();
  }
  @Test void certainPendingAndRejectedInvoiceSkipWithoutAnyNewRequest() {
    String pending = pending(), failed = pending();
    fake.respond("Issue", REJECTED); service.issueOne(failed);
    assertThat(invoice(failed)).containsEntry("status", "FAILED").containsEntry("uncertain", false);
    int before = fake.received().size(); refund(pending); refund(failed); tick();
    assertThat(invoice(pending)).containsEntry("status", "SKIPPED");
    assertThat(invoice(failed)).containsEntry("status", "SKIPPED");
    assertThat(fake.received()).hasSize(before);
  }
  @Test void missingInvoiceInformationOnRefundSkipsWithoutNetwork() {
    String id = pending(); refund(id);
    db.update("update orders set invoice_type=null,invoice_email=null where id=?", id);
    service.issueOne(id);
    assertThat(invoice(id)).containsEntry("status", "SKIPPED").containsEntry("uncertain", false);
    assertThat(fake.received()).isEmpty();
  }
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void refundDuringIssueIsCaughtEvenWhenRefundCursorAlreadyPassed(boolean scan) throws Exception {
    String id = pending();
    var workers = Executors.newSingleThreadExecutor();
    try {
      fake.hook("Issue", ignored -> {
        refund(id);
        if (scan) {
          try { workers.submit(this::tick).get(5, TimeUnit.SECONDS); }
          catch (Exception failure) { throw new AssertionError(failure); }
          assertThat(invoice(id).get("status")).isNotEqualTo("SKIPPED");
          assertThat(reversal(id)).containsEntry("status", "PENDING");
        } else db.update("update invoice_cursors set at_ms=?,order_id=? where name='REFUNDED'", System.currentTimeMillis() + 999999, id);
      });
      service.issueOne(id);
    } finally { workers.shutdownNow(); }
    assertThat(invoice(id)).containsEntry("status", "ISSUED");
    assertThat(reversal(id)).containsEntry("status", "PENDING");
    tick(); done(id, "VOID"); assertThat(count("Issue")).isEqualTo(1);
  }
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void uncertainIssueAfterRefundConfirmsOrCreatesUniqueInvoice(boolean created) {
    String id = pending(); fake.respond("Issue", created ? SUCCEED_THEN_TIMEOUT : TIMEOUT);
    service.issueOne(id); refund(id); tick();
    assertThat(invoice(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true);
    assertThat(reversal(id)).containsEntry("status", "PENDING");
    assertThat(count("Issue")).isEqualTo(1);
    fake.respond("Issue", OK); expireIssue(id); tick();
    assertThat(invoice(id)).containsEntry("status", "ISSUED"); done(id, "VOID");
    assertThat(fake.invoiceCount()).isEqualTo(1);
    assertThat(count("Issue")).isEqualTo(created ? 1 : 2); assertThat(count("Invalid")).isEqualTo(1);
  }
  @Test void uncertainRefundRejectedIssueEndsAsNone() {
    String id = pending(); fake.respond("Issue", TIMEOUT); service.issueOne(id); refund(id); tick();
    fake.respond("Issue", REJECTED); expireIssue(id); tick();
    assertThat(invoice(id)).containsEntry("status", "SKIPPED").containsEntry("uncertain", false).containsEntry("inflight_until", null);
    assertThat(reversal(id)).containsEntry("status", "DONE").containsEntry("kind", "NONE");
    assertThat(fake.invoiceCount()).isZero(); assertThat(count("Invalid") + count("Allowance")).isZero();
  }
  @Test void unresolvedFailedInvoicesDoNotStarveIssuedReversal() {
    for (int n = 0; n < 3; n++) {
      String id = pending(); refund(id);
      db.update("update invoices set status='FAILED',uncertain=true where order_id=?", id);
    }
    String ready = issued(); refund(ready); tick(); done(ready, "VOID");
    assertThat(db.queryForObject("select count(*) from invoice_reversals where status='PENDING'", Integer.class)).isEqualTo(3);
    assertThat(count("Issue")).isEqualTo(1); assertThat(count("Invalid")).isEqualTo(1);
  }
  @Test void staleWorkerCannotSendWhileOwnerIsInFlightThenRefundSkipsRejectedInvoice() throws Exception {
    String id = pending();
    var staleRead = new CountDownLatch(1); var releaseStale = new CountDownLatch(1);
    var inFlight = new CountDownLatch(1); var releaseOwner = new CountDownLatch(1);
    fake.respond("Issue", REJECTED);
    fake.hook("Issue", ignored -> { inFlight.countDown(); await(releaseOwner); });
    var workers = Executors.newFixedThreadPool(2);
    try {
      var b = workers.submit(() -> {
        assertThat(invoice(id)).containsEntry("status", "PENDING").containsEntry("uncertain", false);
        staleRead.countDown(); await(releaseStale);
        assertThat(service.tryClaim(id, System.currentTimeMillis())).isEmpty();
        service.issueOne(id);
      });
      await(staleRead); var a = workers.submit(() -> service.issueOne(id)); await(inFlight);
      releaseStale.countDown(); b.get(5, TimeUnit.SECONDS); releaseOwner.countDown(); a.get(5, TimeUnit.SECONDS);
    } finally { releaseStale.countDown(); releaseOwner.countDown(); workers.shutdownNow(); }
    assertThat(invoice(id)).containsEntry("status", "FAILED").containsEntry("uncertain", false).containsEntry("claim_token", null);
    refund(id); tick(); assertThat(invoice(id)).containsEntry("status", "SKIPPED");
    assertThat(fake.invoiceCount()).isZero(); assertThat(count("Issue")).isEqualTo(1);
  }
  @Test void deadOwnerRefundWaitsForLeaseThenQueriesExistingInvoice() {
    String id = pending();
    assertThat(client.issue(orders.invoiceCandidate(id).orElseThrow(), System.currentTimeMillis() / 1000)).isInstanceOf(Ok.class);
    long seedCount = count("Issue");
    db.update("update invoices set uncertain=true,claim_token='dead',claimed_until=?,inflight_until=? where order_id=?",
        System.currentTimeMillis() + 60000, System.currentTimeMillis() + 660000, id);
    refund(id); tick(); service.issueOne(id);
    assertThat(invoice(id)).containsEntry("status", "PENDING"); assertThat(reversal(id)).containsEntry("status", "PENDING");
    assertThat(count("Issue")).isEqualTo(seedCount);
    db.update("update invoices set claimed_until=? where order_id=?", System.currentTimeMillis() - 1, id);
    expireIssue(id); tick(); done(id, "VOID");
    assertThat(count("Issue")).isEqualTo(seedCount);
  }
  @Test void lateSuccessfulIssuePullsSkippedNoneBackIntoReversal() {
    String id = pending();
    fake.hook("Issue", ignored -> {
      refund(id);
      db.update("update invoices set status='SKIPPED',uncertain=false,claim_token=null,claimed_until=null,inflight_until=null where order_id=?", id);
      db.update("insert into invoice_reversals(order_id,branch_id,status,kind,created_at,updated_at,done_at) values(?,'taipei','DONE','NONE',0,0,0)", id);
    });
    service.issueOne(id);
    assertThat(invoice(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true);
    tick(); tick(); done(id, "VOID"); assertThat(count("Issue")).isEqualTo(1);
  }
  @ParameterizedTest @ValueSource(booleans = {false, true})
  void delayedIssueRefundNeverSkipsAndOnlyResendsAfterBound(boolean apply) {
    String id = pending(); fake.respond("Issue", DELAYED_EFFECT); service.issueOne(id);
    refund(id); tick(); tick();
    assertThat(invoice(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true);
    assertThat(reversal(id)).containsEntry("status", "PENDING"); assertThat(count("Issue")).isEqualTo(1);
    if (apply) fake.applyDelayed(); else fake.dropDelayed();
    fake.respond("Issue", OK); expireIssue(id); tick(); tick();
    assertThat(invoice(id)).containsEntry("status", "ISSUED"); done(id, "VOID");
    assertThat(fake.invoiceCount()).isEqualTo(1); assertThat(count("Issue")).isEqualTo(apply ? 1 : 2);
  }
  @ParameterizedTest @ValueSource(strings = {"Invalid", "Allowance"})
  void delayedReversalPreservesKindAndBoundWithoutEarlyResend(String op) {
    String id = issued(); if (op.equals("Allowance")) expiredDate(id);
    refund(id); fake.respond(op, DELAYED_EFFECT); tick();
    String kind = op.equals("Invalid") ? "VOID" : "ALLOWANCE";
    assertThat(reversal(id)).containsEntry("status", "PENDING").containsEntry("kind", kind).containsEntry("uncertain", true);
    long bound = ((Number) reversal(id).get("inflight_until")).longValue();
    tick(); service.reverseOne(id); // Direct call also honors the durable boundary.
    assertThat(count(op)).isEqualTo(1); assertThat(reversal(id)).containsEntry("attempts", 1).containsEntry("inflight_until", bound);
    assertThat(reversal(id)).containsEntry("last_error", "等待前次作廢／折讓請求失效");
    fake.applyDelayed(); expireReverse(id); tick(); done(id, kind);
    assertThat(count(op)).isEqualTo(1); assertThat(count(op.equals("Invalid") ? "Allowance" : "Invalid")).isZero();
  }
  @Test void reversalClaimExcludesConcurrentWorkerAndHttpDoesNotHoldTransaction() throws Exception {
    String id = issued(); refund(id);
    fake.respond("Invalid", HTTP_500); tick(); expireReverse(id);
    long before = count("Invalid"); fake.respond("Invalid", OK);
    var inFlight = new CountDownLatch(1); var release = new CountDownLatch(1);
    fake.hook("Invalid", ignored -> { inFlight.countDown(); await(release); });
    var workers = Executors.newSingleThreadExecutor();
    try {
      var a = workers.submit(() -> service.reverseOne(id)); await(inFlight);
      service.reverseOne(id); assertThat(count("Invalid")).isEqualTo(before + 1);
      release.countDown(); a.get(5, TimeUnit.SECONDS);
    } finally { release.countDown(); workers.shutdownNow(); }
    done(id, "VOID"); assertThat(count("Allowance")).isZero();
  }
  @Test void deterministicRejectionsAndUnknownAttemptCapHaveDistinctCertainty() {
    String rejected = issued(); refund(rejected); fake.respond("Invalid", REJECTED); fake.respond("Allowance", REJECTED); tick();
    assertThat(reversal(rejected)).containsEntry("status", "FAILED").containsEntry("uncertain", false)
        .containsEntry("kind", "ALLOWANCE").containsEntry("inflight_until", null);
    String unknown = issued(); refund(unknown); fake.respond("Invalid", HTTP_500); tick();
    fake.respond("GetIssue", HTTP_500);
    for (int n = 0; n < 2; n++) { expireReverse(unknown); service.reverseOne(unknown); }
    assertThat(reversal(unknown)).containsEntry("status", "FAILED").containsEntry("uncertain", true).containsEntry("attempts", 3);
    assertThat(reversal(unknown).get("inflight_until")).isNotNull();
  }
  @Test void lostReversalOwnerDiscardsResponseAndNextOwnerQueriesEffect() {
    String id = issued(); refund(id); fake.respond("Invalid", HTTP_500); tick(); expireReverse(id);
    fake.respond("Invalid", OK);
    fake.hook("Invalid", ignored -> db.update("update invoice_reversals set claim_token='replacement',claimed_until=? where order_id=?", System.currentTimeMillis() + 60000, id));
    service.reverseOne(id);
    assertThat(reversal(id)).containsEntry("status", "PENDING").containsEntry("claim_token", "replacement").containsEntry("uncertain", true);
    db.update("update invoice_reversals set claimed_until=? where order_id=?", System.currentTimeMillis() - 1, id);
    expireReverse(id); service.reverseOne(id); done(id, "VOID"); assertThat(count("Invalid")).isEqualTo(2);
  }
  @Test void malformedAllowanceSuccessRemainsUncertainUntilQueryProvesEffect() {
    String id = issued(); expiredDate(id); refund(id); fake.respond("Allowance", MISSING_FIELDS); tick();
    assertThat(reversal(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true).containsEntry("allowance_no", null);
    expireReverse(id); tick(); done(id, "ALLOWANCE"); assertThat(count("Allowance")).isEqualTo(1);
  }
  @Test void malformedQueryDoesNotAuthorizeProviderMutation() {
    String id = issued(); refund(id); fake.respond("Invalid", HTTP_500); tick(); expireReverse(id);
    fake.respond("GetIssue", AMOUNT_MISMATCH); service.reverseOne(id);
    assertThat(reversal(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true).containsEntry("attempts", 2);
    assertThat(count("Invalid")).isEqualTo(1); assertThat(count("Allowance")).isZero();
  }
  @Test void queryRejectedFailsWithoutMutationAndSensitiveProviderMessagesStaySanitized() {
    String id = issued(); refund(id); fake.respond("Invalid", HTTP_500); tick(); expireReverse(id);
    fake.respond("GetIssue", REJECTED); service.reverseOne(id);
    assertThat(reversal(id)).containsEntry("status", "FAILED").containsEntry("uncertain", false)
        .containsEntry("last_error", "查不到這張發票，請人工確認").containsEntry("inflight_until", null);
    fake.respond("GetIssue", OK);
    String sensitive = issued(); expiredDate(sensitive); refund(sensitive); fake.respond("Allowance", ECHO_SENSITIVE); tick();
    assertThat((String) reversal(sensitive).get("last_error")).doesNotContain(KEY, IV, EMAIL, "fake-merchant", "\n", "\t");
  }
}
