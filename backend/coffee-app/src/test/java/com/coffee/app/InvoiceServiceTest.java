package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import static com.coffee.app.ecpay.FakeEinvoiceServer.Scenario.*;

import com.coffee.app.ecpay.FakeEinvoiceServer;
import com.coffee.orders.api.Orders;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:g02-scheduler;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "ecpay.invoice.enabled=true", "orders.invoice-required=true",
    "ecpay.invoice.environment=stage", "ecpay.invoice.merchant-id=fake-merchant",
    "ecpay.invoice.hash-key=TestInvoiceKey12", "ecpay.invoice.hash-iv=TestInvoiceIv123",
    "ecpay.invoice.timeout-ms=1000", "ecpay.invoice.interval-ms=3600000",
    "ecpay.invoice.lag-seconds=120", "ecpay.invoice.max-attempts=3"
})
@ActiveProfiles("dev")
@ExtendWith(OutputCaptureExtension.class)
class InvoiceServiceTest {
  private static final String KEY = "TestInvoiceKey12", IV = "TestInvoiceIv123";
  private static final String EMAIL = "scheduler-private@example.test";
  private static final FakeEinvoiceServer fake = startFake();
  @Autowired Orders orders;
  @Autowired InvoiceService service;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactions;
  private final Actor cashier = new Actor("cashier", "cashier", "收銀員", "CASHIER", "BRANCH",
      "taipei", Set.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE", "PAYMENT_RECONCILE"));

  private static FakeEinvoiceServer startFake() {
    try { return new FakeEinvoiceServer(KEY, IV); }
    catch (Exception failure) { throw new IllegalStateException("測試伺服器啟動失敗"); }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("ecpay.invoice.base-url", fake::baseUrl);
  }

  @BeforeEach
  void reset() {
    db.update("delete from invoices");
    db.update("delete from invoice_cursors");
    // Dedicated context: older fixture orders must not enter the next test's cursor window.
    db.update("update orders set paid_at=null");
    fake.reset();
  }

  @AfterEach
  void logsContainNoSensitiveDataOrStack(CapturedOutput output) {
    assertThat(output.getAll()).doesNotContain(KEY, IV, EMAIL, "/ABC1234", "7654321");
    assertThat(output.getAll().lines().noneMatch(line -> line.startsWith("\tat "))).isTrue();
  }

  @AfterAll static void stopFake() { fake.close(); }

  private String paid(String method) {
    return paid(method, new Orders.InvoiceRequest("CLOUD", EMAIL, null, null));
  }

  private String paid(String method, Orders.InvoiceRequest invoice) {
    var order = orders.create(cashier, new Orders.Create("taipei", "TAKEAWAY", method, "", null,
        List.of(new Orders.LineInput("latte", 1, List.of("temp-hot", "sugar-none"))),
        invoice), UUID.randomUUID().toString());
    if (method.equals("CASH")) orders.cash(cashier, order.id(), order.total());
    else orders.confirmOnline(order.id(), order.total(), UUID.randomUUID().toString(), System.currentTimeMillis());
    return order.id();
  }

  private String pending() {
    String id = paid("CASH");
    enqueue(id);
    return id;
  }

  private void enqueue(String id) {
    db.update("insert into invoices(order_id,branch_id,amount,status,created_at,updated_at) "
        + "select id,branch_id,total,'PENDING',0,0 from orders where id=?", id);
  }

  private Map<String, Object> row(String id) {
    return db.queryForMap("select * from invoices where order_id=?", id);
  }

  private void issued(String id) {
    assertThat(row(id)).containsEntry("status", "ISSUED").containsEntry("uncertain", false)
        .containsEntry("invoice_date", "2026-10-16 12:00:00").containsEntry("random_number", "1234")
        .containsEntry("claim_token", null).containsEntry("claimed_until", null)
        .containsEntry("inflight_until", null);
    assertThat((String) row(id).get("invoice_no")).matches("AB[0-9]{8}");
    assertThat(row(id).get("issued_at")).isNotNull();
  }

  private long issueCount() { return fake.received().stream().filter(r -> r.path().endsWith("/Issue")).count(); }
  private void expire(String id) { db.update("update invoices set inflight_until=? where order_id=?", System.currentTimeMillis() - 1, id); }
  private void tick() { service.runOnce(System.currentTimeMillis()); }
  private void await(CountDownLatch latch) {
    try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError("等待中斷"); }
  }

  @Test
  void initialCursorInclusiveBoundaryLagAndIdempotentEnqueue() {
    long t0 = System.currentTimeMillis(), c = t0 - 120000;
    service.runOnce(t0);
    assertThat(db.queryForMap("select * from invoice_cursors where name='PAID'"))
        .containsEntry("at_ms", c).containsEntry("order_id", "");
    assertThat(fake.received()).isEmpty();
    String before = paid("CASH"), edge = paid("CASH"), lag = paid("ECPAY"), later = paid("CASH");
    String[] ids = {before, edge, lag, later};
    long[] at = {c - 1, c, t0 - 1000, t0 + 1000};
    for (int n = 0; n < ids.length; n++) db.update("update orders set paid_at=? where id=?", at[n], ids[n]);
    service.runOnce(t0);
    issued(edge);
    assertThat(db.queryForObject("select count(*) from invoices", Integer.class)).isEqualTo(1);
    service.runOnce(t0 + 121000);
    issued(lag); issued(later);
    assertThat(db.queryForObject("select count(*) from invoices where order_id=?", Integer.class, before)).isZero();
    db.update("update invoice_cursors set at_ms=?,order_id='' where name='PAID'", c);
    service.runOnce(t0 + 121000);
    assertThat(db.queryForObject("select count(*) from invoices", Integer.class)).isEqualTo(3);
    assertThat(issueCount()).isEqualTo(3);
  }

  @Test
  void cursorEnqueuesAtMost200AndRetainsSameMillisecondRemainder() {
    long at = System.currentTimeMillis() - 200000;
    var ids = new ArrayList<String>();
    for (int n = 0; n < 201; n++) {
      String id = paid("CASH");
      ids.add(id);
      db.update("update orders set paid_at=? where id=?", at, id);
    }
    Collections.sort(ids);
    db.update("insert into invoice_cursors(name,at_ms,order_id) values('PAID',?,'')", at);
    // An existing row must not prevent cursor advancement through the same paid snapshot.
    db.update("insert into invoices(order_id,branch_id,amount,status,created_at,updated_at) "
        + "select id,branch_id,total,'PENDING',0,0 from orders where id=?", ids.get(0));
    tick();
    assertThat(db.queryForObject("select count(*) from invoices", Integer.class)).isEqualTo(200);
    assertThat(db.queryForObject("select order_id from invoice_cursors where name='PAID'", String.class)).isEqualTo(ids.get(199));
    assertThat(issueCount()).isEqualTo(20);
    tick();
    assertThat(db.queryForObject("select count(*) from invoices", Integer.class)).isEqualTo(201);
    assertThat(db.queryForObject("select order_id from invoice_cursors where name='PAID'", String.class)).isEqualTo(ids.get(200));
    assertThat(issueCount()).isEqualTo(40);
  }

  @Test
  void timeoutThenQueryDoesNotIssueTwiceAndDeadLeaseRequiresExpiry() {
    String id = pending();
    fake.respond("Issue", SUCCEED_THEN_TIMEOUT);
    service.issueOne(id);
    assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true)
        .containsEntry("attempts", 1).containsEntry("claim_token", null).containsEntry("claimed_until", null);
    assertThat(row(id).get("inflight_until")).isNotNull();
    db.update("update invoices set claim_token='dead',claimed_until=? where order_id=?", System.currentTimeMillis() + 60000, id);
    int requests = fake.received().size();
    service.issueOne(id);
    assertThat(fake.received()).hasSize(requests);
    db.update("update invoices set claimed_until=? where order_id=?", System.currentTimeMillis() - 1, id);
    service.issueOne(id);
    issued(id);
    assertThat(fake.received().get(requests).path()).endsWith("/GetIssue");
    assertThat(issueCount()).isEqualTo(1);
  }

  @Test
  void duplicateQueriesWithinSameClaimAndAmountMismatchNeverReissues() {
    String id = pending();
    fake.respond("Issue", DUPLICATE);
    service.issueOne(id);
    issued(id);
    assertThat(fake.received()).hasSize(2);
    assertThat(fake.received().get(1).path()).endsWith("/GetIssue");
    db.update("update invoices set status='PENDING',uncertain=true where order_id=?", id);
    fake.respond("GetIssue", AMOUNT_MISMATCH);
    service.issueOne(id);
    assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("uncertain", true)
        .containsEntry("last_error", "綠界已有同編號發票但金額不符");
    assertThat(issueCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"OTHER_REJECTED", "MISSING_FIELDS", "STRING_AMOUNT"})
  void queryOnlyTreatsNotFoundAsAbsenceAndRequiresCompleteSuccess(String scenario) {
    String id = pending();
    service.issueOne(id);
    db.update("update invoices set status='PENDING',uncertain=true,inflight_until=? where order_id=?",
        System.currentTimeMillis() + 60000, id);
    Object boundary = row(id).get("inflight_until");
    fake.respond("GetIssue", FakeEinvoiceServer.Scenario.valueOf(scenario));
    service.issueOne(id);
    if (scenario.equals("STRING_AMOUNT")) issued(id);
    else assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true)
        .containsEntry("attempts", 1).containsEntry("inflight_until", boundary);
    assertThat(issueCount()).isEqualTo(1);
  }

  @Test
  void rejectionClearsBoundaryButUnknownExhaustionPreservesIt() {
    String id = pending();
    fake.respond("Issue", REJECTED);
    service.issueOne(id);
    assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("uncertain", false)
        .containsEntry("inflight_until", null).containsEntry("claim_token", null).containsEntry("claimed_until", null);
    assertThat((String) row(id).get("last_error")).contains("4000001");
    int count = fake.received().size();
    service.issueOne(id);
    assertThat(fake.received()).hasSize(count);
    String unknown = pending();
    fake.respond("Issue", TIMEOUT);
    fake.respond("GetIssue", TIMEOUT);
    service.issueOne(unknown);
    Object boundary = row(unknown).get("inflight_until");
    service.issueOne(unknown); service.issueOne(unknown);
    assertThat(row(unknown)).containsEntry("status", "FAILED").containsEntry("uncertain", true)
        .containsEntry("attempts", 3).containsEntry("inflight_until", boundary)
        .containsEntry("last_error", "連續 3 次無法確認開立結果");
  }

  @Test
  void missingInvoiceAndChangedAmountFailWithoutNetwork() {
    Object orderService = AopTestUtils.getUltimateTargetObject(orders);
    String missing;
    ReflectionTestUtils.setField(orderService, "invoiceRequired", false);
    try { missing = paid("CASH", null); }
    finally { ReflectionTestUtils.setField(orderService, "invoiceRequired", true); }
    enqueue(missing);
    service.issueOne(missing);
    assertThat(row(missing)).containsEntry("status", "FAILED").containsEntry("uncertain", false)
        .containsEntry("last_error", "訂單缺少發票資訊");
    String changed = pending();
    db.update("update invoices set amount=amount+1 where order_id=?", changed);
    service.issueOne(changed);
    assertThat(row(changed)).containsEntry("status", "FAILED")
        .containsEntry("last_error", "訂單金額已變動，請人工確認");
    assertThat(fake.received()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"CLOUD", "MOBILE", "DONATE"})
  void allInvoiceTypesCompleteWithoutLoggingPrivateFields(String type) {
    String id = paid("CASH", new Orders.InvoiceRequest(type, EMAIL,
        type.equals("MOBILE") ? "/ABC1234" : null, type.equals("DONATE") ? "7654321" : null));
    enqueue(id);
    service.issueOne(id);
    issued(id);
    assertThat(issueCount()).isEqualTo(1);
  }

  @Test
  void losingRejectedHolderCannotClearOthersUncertainty() {
    String id = pending();
    fake.respond("Issue", REJECTED);
    fake.hook("Issue", request -> {
      var inflight = row(id);
      assertThat(inflight).containsEntry("uncertain", true);
      assertThat(inflight.get("claim_token")).isNotNull();
      assertThat(inflight.get("inflight_until")).isEqualTo(request.timestampSec() * 1000 + 660000);
      db.update("update invoices set claim_token='other' where order_id=?", id);
    });
    service.issueOne(id);
    assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true)
        .containsEntry("claim_token", "other");
  }

  @Test
  void onlyOneWorkerClaimsIn20SynchronizedRaces() throws Exception {
    String id = pending();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 20; i++) {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        long now = System.currentTimeMillis();
        Callable<Optional<InvoiceService.Claim>> claim = () -> {
          assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("uncertain", false)
              .containsEntry("claim_token", null);
          ready.countDown(); await(go);
          return service.tryClaim(id, now);
        };
        var a = pool.submit(claim); var b = pool.submit(claim);
        await(ready); go.countDown();
        var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(row(id).get("claimed_until")).isEqualTo(now + 900000);
        assertThat(results.stream().flatMap(Optional::stream).findFirst().orElseThrow().token())
            .isEqualTo(row(id).get("claim_token"));
        db.update("update invoices set claim_token=null,claimed_until=null where order_id=?", id);
      }
    } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    assertThat(fake.received()).isEmpty();
  }

  @Test
  void activeIssueExcludesOtherWorkerWithoutHoldingDatabaseLocks() throws Exception {
    String id = pending();
    var entered = new CountDownLatch(1); var go = new CountDownLatch(1);
    fake.hook("Issue", request -> { entered.countDown(); await(go); });
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      var first = pool.submit(() -> service.issueOne(id));
      await(entered);
      Object token = row(id).get("claim_token");
      service.issueOne(id);
      assertThat(fake.received()).hasSize(1);
      assertThat(row(id).get("claim_token")).isEqualTo(token);
      go.countDown(); first.get(10, TimeUnit.SECONDS);
      issued(id);
      assertThat(issueCount()).isEqualTo(1);
    } finally { go.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
  }

  @Test
  void inflightBoundaryBlocksBatchAndDirectIssueButNotConfirmation() {
    String id = pending();
    long boundary = System.currentTimeMillis() + 60000;
    db.update("update invoices set uncertain=true,inflight_until=? where order_id=?", boundary, id);
    tick();
    assertThat(fake.received()).isEmpty();
    service.issueOne(id);
    assertThat(issueCount()).isZero();
    assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true)
        .containsEntry("attempts", 0).containsEntry("inflight_until", boundary)
        .containsEntry("last_error", "等待前次開立請求失效").containsEntry("claim_token", null);
    expire(id); tick(); issued(id);
    assertThat(fake.received().stream().map(r -> r.path().substring(r.path().lastIndexOf('/') + 1)))
        .containsExactly("GetIssue", "GetIssue", "Issue");
  }

  @ParameterizedTest
  @ValueSource(strings = {"FAILED", "SKIPPED", "PENDING"})
  void lateOkCompensatesWithoutStealingOtherClaim(String state) {
    String id = pending();
    fake.hook("Issue", request -> db.update("update invoices set status=?,uncertain=false,"
        + "claim_token=?,claimed_until=?,inflight_until=null where order_id=?", state,
        state.equals("PENDING") ? "other" : null, state.equals("PENDING") ? System.currentTimeMillis() + 60000 : null, id));
    service.issueOne(id);
    assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true);
    if (state.equals("PENDING")) assertThat(row(id)).containsEntry("claim_token", "other");
    else {
      assertThat(row(id)).containsEntry("last_error", "收到遲到的開立回應，待確認");
      service.issueOne(id); issued(id);
    }
    assertThat(issueCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void delayedEffectCannotCauseASecondIssueUntilBoundaryExpires(boolean applies) {
    String id = pending();
    fake.respond("Issue", DELAYED_EFFECT);
    service.issueOne(id);
    Object boundary = row(id).get("inflight_until");
    tick(); service.issueOne(id);
    assertThat(issueCount()).isEqualTo(1);
    assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true)
        .containsEntry("inflight_until", boundary).containsEntry("claim_token", null);
    if (applies) fake.applyDelayed(); else fake.dropDelayed();
    fake.respond("Issue", OK);
    expire(id); tick(); issued(id);
    assertThat(fake.invoiceCount()).isEqualTo(1);
    assertThat(row(id).get("invoice_no")).isEqualTo("AB00000001");
    assertThat(issueCount()).isEqualTo(applies ? 1 : 2);
  }

  @Test
  void postClaimSnapshotWinsOverOrderReadBeforeAnotherWorkersTimeout() throws Exception {
    String id = pending();
    fake.respond("Issue", DELAYED_EFFECT);
    var read = new CountDownLatch(1); var go = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      var b = pool.submit(() -> service.issueOne(id, () -> { read.countDown(); await(go); }));
      await(read); service.issueOne(id);
      Object boundary = row(id).get("inflight_until");
      go.countDown(); b.get(10, TimeUnit.SECONDS);
      assertThat(issueCount()).isEqualTo(1);
      assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("uncertain", true)
          .containsEntry("inflight_until", boundary).containsEntry("last_error", "等待前次開立請求失效");
    } finally { go.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
  }

  @Test
  void ambientTransactionIsSuspendedAndHttpSeesCommittedInflightState() {
    String id = pending();
    fake.hook("Issue", request -> {
      assertThat(row(id)).containsEntry("uncertain", true);
      // A separate DB connection can update the row while the caller is waiting on HTTP.
      db.update("update invoices set last_error='測試已讀取已提交狀態' where order_id=?", id);
    });
    new TransactionTemplate(transactions).executeWithoutResult(ignored -> service.issueOne(id));
    issued(id);
  }
}
