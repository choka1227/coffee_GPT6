package com.coffee.payments.internal;

import com.coffee.orders.api.Orders;
import com.coffee.orders.api.Orders.InvoiceCandidate;
import com.coffee.payments.api.Invoices;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import com.coffee.payments.internal.EcpayInvoiceClient.*;
import java.util.*;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable invoice queue. All network calls run outside transactions, including caller transactions. */
@Service
public class InvoiceService implements Invoices {
  public static final long STALE_REQUEST_WINDOW_MS = 660000;
  private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);
  private final Orders orders;
  private final EcpayInvoiceClient client;
  private final JdbcTemplate db;
  private final TransactionTemplate tx, outside;
  private final boolean enabled;
  private final long lagMs, leaseMs;
  private final int batch, maxAttempts;
  private static final String INVOICE_SELECT = "select i.*,r.kind as reversal_kind,r.status as reversal_status,r.allowance_no,r.last_error as reversal_error "
      + "from invoices i left join invoice_reversals r on r.order_id=i.order_id ";
  private static final String REVERSAL_SELECT = "select r.*,i.invoice_no,i.amount from invoice_reversals r "
      + "join invoices i on i.order_id=r.order_id ";

  public record Claim(String token, int amount, int attempts, boolean uncertain, Long inflightUntil) {}
  private record Outcome(String status, boolean uncertain, boolean clearBound, int attempts,
                         String error, String number, String date, String random) {}

  private Invoice readInvoice(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new Invoice(row.getString("order_id"), row.getString("branch_id"), row.getInt("amount"),
        row.getString("status"), row.getString("invoice_no"), row.getString("invoice_date"),
        row.getString("random_number"), row.getInt("attempts"), row.getString("last_error"), row.getLong("updated_at"),
        row.getString("reversal_kind"), row.getString("reversal_status"), row.getString("allowance_no"), row.getString("reversal_error"));
  }

  @Override
  public Optional<Invoice> forOrder(Actor actor, String orderId) {
    orders.get(actor, orderId);
    return db.query(INVOICE_SELECT + "where i.order_id=?", this::readInvoice, orderId).stream().findFirst();
  }

  private void requireStaff(Actor actor) {
    actor.require("PAYMENT_RECONCILE");
    if (actor.customer()) throw new Problem(403, "顧客不能管理發票");
  }

  @Override
  public InvoicePage failed(Actor actor, String branchId) {
    requireStaff(actor);
    String branch = branchId == null || branchId.isBlank() ? null : branchId;
    if (!actor.global()) {
      if (branch != null) actor.branch(branch);
      branch = actor.branchId();
      if (branch == null) throw new Problem(403, "只能存取所屬分店資料");
    }
    List<Invoice> rows = branch == null
        ? db.query(INVOICE_SELECT + "where i.status='FAILED' order by i.updated_at desc,i.order_id limit 201", this::readInvoice)
        : db.query(INVOICE_SELECT + "where i.status='FAILED' and i.branch_id=? order by i.updated_at desc,i.order_id limit 201", this::readInvoice, branch);
    return new InvoicePage(List.copyOf(rows.subList(0, Math.min(200, rows.size()))), rows.size() > 200);
  }

  @Override
  public Invoice retry(Actor actor, String orderId) {
    requireStaff(actor);
    return tx.execute(ignored -> {
      var invoice = db.query(INVOICE_SELECT + "where i.order_id=?", this::readInvoice, orderId)
          .stream().findFirst().orElseThrow(() -> new Problem(404, "找不到這筆發票"));
      actor.branch(invoice.branchId());
      if (db.update("update invoices set status='PENDING',attempts=0,uncertain=true,claim_token=null,"
          + "claimed_until=null,last_error=null,updated_at=? where order_id=? and status='FAILED'",
          System.currentTimeMillis(), orderId) == 0)
        throw new Problem(409, "這張發票目前不需要重試");
      return db.queryForObject(INVOICE_SELECT + "where i.order_id=?", this::readInvoice, orderId);
    });
  }

  private Reversal readReversal(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Reversal(r.getString("order_id"), r.getString("branch_id"), r.getString("invoice_no"),
        r.getInt("amount"), r.getString("kind"), r.getString("status"), r.getString("allowance_no"),
        r.getInt("attempts"), r.getString("last_error"), r.getLong("updated_at"));
  }

  @Override
  public ReversalPage failedReversals(Actor actor, String branchId) {
    requireStaff(actor);
    String branch = branchId == null || branchId.isBlank() ? null : branchId;
    if (!actor.global()) {
      if (branch != null) actor.branch(branch);
      branch = actor.branchId();
      if (branch == null) throw new Problem(403, "只能存取所屬分店資料");
    }
    String query = REVERSAL_SELECT + "where r.status='FAILED'";
    List<Reversal> rows = branch == null
        ? db.query(query + " order by r.updated_at desc,r.order_id limit 201", this::readReversal)
        : db.query(query + " and r.branch_id=? order by r.updated_at desc,r.order_id limit 201", this::readReversal, branch);
    return new ReversalPage(List.copyOf(rows.subList(0, Math.min(200, rows.size()))), rows.size() > 200);
  }

  @Override
  public Reversal retryReversal(Actor actor, String orderId) {
    requireStaff(actor);
    return tx.execute(ignored -> {
      var row = db.query(REVERSAL_SELECT + "where r.order_id=?", this::readReversal, orderId)
          .stream().findFirst().orElseThrow(() -> new Problem(404, "找不到這筆發票"));
      actor.branch(row.branchId());
      if (db.update("update invoice_reversals set status='PENDING',attempts=0,uncertain=true,"
          + "claim_token=null,claimed_until=null,last_error=null,updated_at=? where order_id=? and status='FAILED'",
          System.currentTimeMillis(), orderId) == 0)
        throw new Problem(409, "這筆作廢／折讓目前不需要重試");
      return db.queryForObject(REVERSAL_SELECT + "where r.order_id=?", this::readReversal, orderId);
    });
  }

  public InvoiceService(Orders orders, EcpayInvoiceClient client, JdbcTemplate db,
      PlatformTransactionManager transactions,
      @Value("${ecpay.invoice.enabled:false}") boolean enabled,
      @Value("${ecpay.invoice.lag-seconds:120}") long lagSeconds,
      @Value("${ecpay.invoice.batch-size:20}") int batch,
      @Value("${ecpay.invoice.max-attempts:10}") int maxAttempts,
      @Value("${ecpay.invoice.claim-lease-ms:900000}") long leaseMs) {
    this.orders = orders;
    this.client = client; // Client validates the complete enabled configuration at startup.
    this.db = db;
    this.enabled = enabled;
    this.lagMs = lagSeconds * 1000;
    this.batch = batch;
    this.maxAttempts = maxAttempts;
    this.leaseMs = leaseMs;
    tx = new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    outside = new TransactionTemplate(transactions);
    outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
  }

  @Scheduled(fixedDelayString = "${ecpay.invoice.interval-ms:300000}")
  public void scheduled() {
    if (!enabled) return;
    try {
      runOnce(System.currentTimeMillis());
    } catch (RuntimeException failure) {
      log.warn("綠界發票：批次處理失敗");
    }
  }

  /** Explicit queue clock for deterministic cutoff tests; HTTP timestamps always use current time. */
  public void runOnce(long now) {
    if (!enabled) return;
    outside.executeWithoutResult(ignored -> runBatch(now));
  }

  private void runBatch(long now) {
    // A collision rolls back both the refund writes and its cursor. Retry next round.
    try { scanRefunds(now); }
    catch (DuplicateKeyException cursorOrEnqueueRace) {
      log.warn("綠界發票：退款掃描競態，下一輪重試");
    }
    int queued;
    try {
      queued = enqueue(now);
    } catch (DuplicateKeyException firstCursorRace) {
      // Retry only after the losing transaction rolled back (PostgreSQL aborts it on conflict).
      queued = enqueue(now);
    }
    int issued = 0, failed = 0, pending = 0;
    var ids = db.queryForList("select order_id from invoices where status='PENDING' "
        + "and (inflight_until is null or inflight_until<=?) order by updated_at,order_id limit ?",
        String.class, now, batch);
    for (String id : ids) {
      try {
        issueOne(id);
        String status = db.queryForObject("select status from invoices where order_id=?", String.class, id);
        if ("ISSUED".equals(status)) issued++;
        else if ("FAILED".equals(status)) failed++;
        else pending++;
      } catch (RuntimeException failure) {
        // Never log exception messages/causes: they may contain request or database values.
        log.warn("綠界發票：orderId={}、處理失敗", id);
        pending++;
      }
    }
    int voided = 0, allowed = 0, reversalFailed = 0;
    var reversalIds = db.queryForList("select r.order_id from invoice_reversals r join invoices i "
        + "on i.order_id=r.order_id where r.status='PENDING' and i.status='ISSUED' "
        + "and (r.inflight_until is null or r.inflight_until<=?) order by r.updated_at,r.order_id limit ?",
        String.class, now, batch);
    for (String id : reversalIds) {
      try {
        reverseOne(id);
        var row = db.queryForMap("select status,kind from invoice_reversals where order_id=?", id);
        if ("FAILED".equals(row.get("status"))) reversalFailed++;
        else if ("DONE".equals(row.get("status"))) {
          if ("VOID".equals(row.get("kind"))) voided++;
          else if ("ALLOWANCE".equals(row.get("kind"))) allowed++;
        }
      } catch (RuntimeException failure) {
        log.warn("綠界發票：orderId={}、作廢折讓處理失敗", id);
      }
    }
    if (queued + issued + failed + pending + reversalIds.size() != 0)
      log.info("綠界發票：入列 {}、開立 {}、失敗 {}、待確認 {}、作廢 {}、折讓 {}、作廢折讓失敗 {}",
          queued, issued, failed, pending, voided, allowed, reversalFailed);
  }

  private void scanRefunds(long now) {
    tx.executeWithoutResult(ignored -> {
      var cursors = db.queryForList("select at_ms,order_id from invoice_cursors where name='REFUNDED' for update");
      if (cursors.isEmpty()) {
        db.update("insert into invoice_cursors(name,at_ms,order_id) values('REFUNDED',?,'')", now - lagMs);
        return;
      }
      var cursor = cursors.get(0);
      var candidates = orders.refundedCandidates(((Number) cursor.get("at_ms")).longValue(),
          (String) cursor.get("order_id"), now - lagMs, 200);
      for (var refund : candidates) {
        String id = refund.orderId();
        var invoices = db.queryForList("select status from invoices where order_id=?", id);
        if (invoices.isEmpty()) {
          var order = orders.invoiceCandidate(id).orElseThrow();
          db.update("insert into invoices(order_id,branch_id,amount,status,last_error,created_at,updated_at) "
              + "values(?,?,?,'SKIPPED','訂單已退款，不需開立',?,?)", id, refund.branchId(), order.total(), now, now);
        } else if ("ISSUED".equals(invoices.get(0).get("status"))) {
          ensureReversalInTransaction(id, now);
        } else if (!"SKIPPED".equals(invoices.get(0).get("status"))) {
          if (skipCertainRefund(id, now, true) == 0) {
            var latest = db.queryForMap("select status,uncertain,claim_token,inflight_until from invoices where order_id=?", id);
            if ("ISSUED".equals(latest.get("status")) ||
                (Set.of("PENDING", "FAILED").contains(latest.get("status")) &&
                 (Boolean.TRUE.equals(latest.get("uncertain")) || latest.get("claim_token") != null || latest.get("inflight_until") != null)))
              ensureReversalInTransaction(id, now);
          }
        }
      }
      if (!candidates.isEmpty()) {
        var last = candidates.get(candidates.size() - 1);
        db.update("update invoice_cursors set at_ms=?,order_id=? where name='REFUNDED'", last.refundedAt(), last.orderId());
      }
    });
  }

  private int skipCertainRefund(String id, long now, boolean includeFailed) {
    return db.update("update invoices set status='SKIPPED',last_error='訂單已退款，不需開立',updated_at=? "
        + "where order_id=? and " + (includeFailed ? "status in ('PENDING','FAILED')" : "status='PENDING'")
        + " and uncertain=false and claim_token is null and inflight_until is null", now, id);
  }

  private void ensureReversalInTransaction(String id, long now) {
    db.update("insert into invoice_reversals(order_id,branch_id,status,created_at,updated_at) "
        + "select order_id,branch_id,'PENDING',?,? from invoices where order_id=? "
        + "and not exists(select 1 from invoice_reversals where order_id=?)", now, now, id, id);
    db.update("update invoice_reversals set status='PENDING',kind=null,uncertain=false,attempts=0,"
        + "done_at=null,last_error=null,updated_at=? where order_id=? and status='DONE' and kind='NONE'", now, id);
  }

  private void noReversalNeeded(String id, long now) {
    db.update("update invoice_reversals set status='DONE',kind='NONE',done_at=?,updated_at=?,"
        + "uncertain=false,inflight_until=null,claim_token=null,claimed_until=null,last_error=null "
        + "where order_id=? and status='PENDING' and kind is null",
        now, now, id);
  }

  private int enqueue(long now) {
    return tx.execute(ignored -> {
      var cursor = db.queryForList("select at_ms,order_id from invoice_cursors where name='PAID' for update");
      if (cursor.isEmpty()) {
        db.update("insert into invoice_cursors(name,at_ms,order_id) values('PAID',?,'')", now - lagMs);
        return 0;
      }
      var row = cursor.get(0);
      var candidates = orders.invoiceCandidates(((Number) row.get("at_ms")).longValue(),
          (String) row.get("order_id"), now - lagMs, 200);
      int inserted = 0;
      for (var candidate : candidates) {
        inserted += db.update("insert into invoices(order_id,branch_id,amount,status,created_at,updated_at) "
            + "select ?,?,?,'PENDING',?,? where not exists(select 1 from invoices where order_id=?)",
            candidate.orderId(), candidate.branchId(), candidate.total(), now, now, candidate.orderId());
      }
      if (!candidates.isEmpty()) {
        var last = candidates.get(candidates.size() - 1);
        db.update("update invoice_cursors set at_ms=?,order_id=? where name='PAID'", last.paidAt(), last.orderId());
      }
      return inserted;
    });
  }

  /** Acquires a lease and returns only a post-claim snapshot, never an unverified token. */
  public Optional<Claim> tryClaim(String orderId, long now) {
    if (!enabled) return Optional.empty();
    return tx.execute(ignored -> {
      String token = UUID.randomUUID().toString();
      int changed = db.update("update invoices set claim_token=?,claimed_until=?,updated_at=? "
          + "where order_id=? and status='PENDING' and (claim_token is null or claimed_until<?)",
          token, now + leaseMs, now, orderId, now);
      if (changed == 0) return Optional.empty();
      return db.query("select amount,attempts,uncertain,inflight_until from invoices "
          + "where order_id=? and claim_token=?", (rs, n) -> new Claim(token, rs.getInt(1),
              rs.getInt(2), rs.getBoolean(3), rs.getObject(4, Long.class)), orderId, token)
          .stream().findFirst();
    });
  }

  public void issueOne(String orderId) { issueOne(orderId, () -> {}); }

  /** Hook is deliberately before claim; tests exercise a stale order-read interleaving. */
  public void issueOne(String orderId, Runnable afterOrderRead) {
    if (!enabled) return;
    outside.executeWithoutResult(ignored -> {
      var candidate = orders.invoiceCandidate(orderId);
      if (candidate.isEmpty()) return;
      var order = candidate.get();
      afterOrderRead.run();
      if (order.invoice() == null) {
        tx.executeWithoutResult(status -> db.update("update invoices set status=?,uncertain=false,"
            + "last_error=?,updated_at=? where order_id=? and status='PENDING' "
            + "and claim_token is null and inflight_until is null",
            order.refundedAt() == null ? "FAILED" : "SKIPPED",
            order.refundedAt() == null ? "訂單缺少發票資訊" : "訂單已退款，不需開立", System.currentTimeMillis(), orderId));
        return;
      }
      if (order.refundedAt() != null) {
        boolean skipped = tx.execute(ignoredTx -> {
          long at = System.currentTimeMillis();
          if (skipCertainRefund(orderId, at, false) == 0) return false;
          noReversalNeeded(orderId, at);
          return true;
        });
        if (skipped) return;
        // tryClaim below performs the authoritative reread after the conditional update lost.
      }
      var claim = tryClaim(orderId, System.currentTimeMillis());
      if (claim.isEmpty()) return;
      Claim snap = claim.get();
      Outcome result;
      if (order.total() != snap.amount()) {
        result = failure(snap, "訂單金額已變動，請人工確認");
      } else {
        result = null;
        if (snap.uncertain()) {
          long qAt = System.currentTimeMillis();
          CallResult queried = client.getIssueByRelateNumber(orderId);
          if (queried instanceof Ok ok) result = confirmed(snap, ok, true);
          else if (queried instanceof Rejected rejected && rejected.rtnCode() == 1600003) {
            if (snap.inflightUntil() != null && qAt < snap.inflightUntil())
              result = new Outcome("PENDING", true, false, snap.attempts(), "等待前次開立請求失效", null, null, null);
          } else result = unknown(snap);
        }
        if (result == null) result = issue(order, snap);
      }
      if (result != null) save(orderId, snap, result);
    });
  }

  private Outcome issue(InvoiceCandidate order, Claim snap) {
    long now = System.currentTimeMillis(), timestamp = now / 1000;
    int changed = tx.execute(ignored -> db.update("update invoices set uncertain=true,inflight_until=?,"
        + "updated_at=? where order_id=? and claim_token=?",
        timestamp * 1000 + STALE_REQUEST_WINDOW_MS, now, order.orderId(), snap.token()));
    if (changed == 0) return null;
    CallResult response = client.issue(order, timestamp);
    if (response instanceof Ok ok) return confirmed(snap, ok, false);
    if (response instanceof Rejected rejected) {
      if (rejected.rtnCode() == 10000009) {
        CallResult queried = client.getIssueByRelateNumber(order.orderId());
        return queried instanceof Ok ok ? confirmed(snap, ok, true) : unknown(snap);
      }
      String message = rejected.rtnMsg();
      return new Outcome("FAILED", false, true, snap.attempts(), "綠界拒絕開立（"
          + rejected.rtnCode() + "）：" + message.substring(0, Math.min(100, message.length())), null, null, null);
    }
    return unknown(snap);
  }

  private Outcome confirmed(Claim snap, Ok response, boolean query) {
    var data = response.data();
    if (query) {
      String amount = Objects.toString(data.get("IIS_Sales_Amount"), "");
      if (!amount.matches("[0-9]+")) return unknown(snap);
      try {
        if (Integer.parseInt(amount) != snap.amount())
          return failure(snap, "綠界已有同編號發票但金額不符");
      } catch (NumberFormatException invalidAmount) {
        return unknown(snap);
      }
    }
    String number = Objects.toString(data.get(query ? "IIS_Number" : "InvoiceNo"), "");
    String date = Objects.toString(data.get(query ? "IIS_Create_Date" : "InvoiceDate"), "");
    String random = Objects.toString(data.get(query ? "IIS_Random_Number" : "RandomNumber"), "");
    if (!number.matches("[A-Z]{2}[0-9]{8}") || date.length() != 19 || !random.matches("[0-9]{4}"))
      return unknown(snap);
    return new Outcome("ISSUED", false, true, snap.attempts(), null, number, date, random);
  }

  private Outcome failure(Claim snap, String error) {
    return new Outcome("FAILED", true, false, snap.attempts(), error, null, null, null);
  }

  private Outcome unknown(Claim snap) {
    int attempts = snap.attempts() + 1;
    return new Outcome(attempts >= maxAttempts ? "FAILED" : "PENDING", true, false, attempts,
        attempts >= maxAttempts ? "連續 " + attempts + " 次無法確認開立結果" : "無法確認開立結果", null, null, null);
  }

  private void save(String orderId, Claim snap, Outcome result) {
    long now = System.currentTimeMillis();
    tx.executeWithoutResult(ignored -> {
      boolean refundRejected = "FAILED".equals(result.status()) && !result.uncertain()
          && orders.invoiceCandidate(orderId).map(o -> o.refundedAt() != null).orElse(false);
      int changed = db.update("update invoices set status=?,uncertain=?,attempts=?,last_error=?,"
          + "invoice_no=?,invoice_date=?,random_number=?,issued_at=?,"
          + "inflight_until=case when ? then null else inflight_until end,"
          + "claim_token=null,claimed_until=null,updated_at=? where order_id=? and claim_token=?",
          refundRejected ? "SKIPPED" : result.status(), result.uncertain(), result.attempts(), result.error(), result.number(),
          result.date(), result.random(), "ISSUED".equals(result.status()) ? now : null,
          result.clearBound(), now, orderId, snap.token());
      if (changed == 1 && refundRejected) noReversalNeeded(orderId, now);
      if (changed == 0 && "ISSUED".equals(result.status())) {
        db.update("update invoices set status='PENDING',uncertain=true,claim_token=null,claimed_until=null,"
            + "last_error='收到遲到的開立回應，待確認',updated_at=? where order_id=? and status in ('FAILED','SKIPPED')", now, orderId);
        db.update("update invoices set uncertain=true where order_id=? and status='PENDING'", orderId);
        log.warn("綠界發票：orderId={}、遲到的開立回應", orderId);
      }
    });
    // Read after committing ISSUED as well: a refund can commit during the HTTP call or save.
    if ("ISSUED".equals(result.status()) && orders.invoiceCandidate(orderId)
        .map(o -> o.refundedAt() != null).orElse(false))
      tx.executeWithoutResult(ignored -> ensureReversalInTransaction(orderId, System.currentTimeMillis()));
  }

  private record ReversalClaim(String token, String kind, int attempts, boolean uncertain,
      Long inflightUntil, String number, String date, int amount, String invoiceStatus) {}
  private record ReversalOutcome(String status, String kind, boolean uncertain, boolean clearBound,
      int attempts, String error, String allowanceNo) {}

  /** Same NOT_SUPPORTED/short REQUIRES_NEW discipline as Issue. No HTTP holds a DB lock. */
  public void reverseOne(String orderId) {
    if (!enabled) return;
    outside.executeWithoutResult(ignored -> {
      long now = System.currentTimeMillis();
      ReversalClaim snap = tx.execute(claimTx -> {
        String token = UUID.randomUUID().toString();
        if (db.update("update invoice_reversals set claim_token=?,claimed_until=?,updated_at=? "
            + "where order_id=? and status='PENDING' and (claim_token is null or claimed_until<?)",
            token, now + leaseMs, now, orderId, now) == 0) return null;
        return db.query("select r.kind,r.attempts,r.uncertain,r.inflight_until,i.invoice_no,i.invoice_date,i.amount,i.status "
            + "from invoice_reversals r join invoices i on i.order_id=r.order_id where r.order_id=? and r.claim_token=?",
            (rs, n) -> new ReversalClaim(token, rs.getString(1), rs.getInt(2), rs.getBoolean(3),
                rs.getObject(4, Long.class), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8)), orderId, token)
            .stream().findFirst().orElse(null);
      });
      if (snap == null) return;
      if (!"ISSUED".equals(snap.invoiceStatus())) {
        tx.executeWithoutResult(release -> db.update("update invoice_reversals set claim_token=null,claimed_until=null "
            + "where order_id=? and claim_token=?", orderId, snap.token()));
        return;
      }
      ReversalOutcome result = null;
      if (snap.uncertain()) {
        long qAt = System.currentTimeMillis();
        CallResult response = client.getIssueByRelateNumber(orderId);
        if (response instanceof Ok ok) {
          var data = ok.data();
          String invalid = Objects.toString(data.get("IIS_Invalid_Status"), "");
          String remaining = Objects.toString(data.get("IIS_Remain_Allowance_Amt"), "");
          // A malformed or different invoice cannot authorize another provider mutation.
          boolean valid = Objects.equals(snap.number(), data.get("IIS_Number"))
              && Objects.toString(data.get("IIS_Sales_Amount"), "").equals(Integer.toString(snap.amount()))
              && invalid.matches("[01]") && remaining.matches("[0-9]{1,9}");
          if (!valid) result = reversalUnknown(snap);
          else if ("1".equals(invalid)) result = reversalDone(snap, "VOID", null);
          else if (Integer.parseInt(remaining) == 0) result = reversalDone(snap, "ALLOWANCE", null);
          else if (snap.inflightUntil() != null && qAt < snap.inflightUntil())
            result = new ReversalOutcome("PENDING", snap.kind(), true, false, snap.attempts(), "等待前次作廢／折讓請求失效", null);
        } else if (response instanceof Rejected)
          result = new ReversalOutcome("FAILED", snap.kind(), false, true, snap.attempts(), "查不到這張發票，請人工確認", null);
        else result = reversalUnknown(snap);
      }
      if (result == null) {
        String kind = snap.kind() != null ? snap.kind()
            : InvoiceDeadline.canVoid(snap.date(), Instant.now()) ? "VOID" : "ALLOWANCE";
        result = sendReversal(orderId, snap, kind);
      }
      if (result == null) return; // Lost ownership before send.
      ReversalOutcome outcome = result;
      tx.executeWithoutResult(saveTx -> db.update("update invoice_reversals set status=?,kind=coalesce(?,kind),"
          + "uncertain=?,attempts=?,last_error=?,allowance_no=?,done_at=?,"
          + "inflight_until=case when ? then null else inflight_until end,claim_token=null,claimed_until=null,updated_at=? "
          + "where order_id=? and claim_token=?", outcome.status(), outcome.kind(), outcome.uncertain(),
          outcome.attempts(), outcome.error(), outcome.allowanceNo(), "DONE".equals(outcome.status()) ? System.currentTimeMillis() : null,
          outcome.clearBound(), System.currentTimeMillis(), orderId, snap.token()));
    });
  }

  private ReversalOutcome sendReversal(String id, ReversalClaim snap, String kind) {
    long now = System.currentTimeMillis(), timestamp = now / 1000;
    int changed = tx.execute(ignored -> db.update("update invoice_reversals set kind=?,uncertain=true,"
        + "inflight_until=?,updated_at=? where order_id=? and claim_token=?", kind,
        timestamp * 1000 + STALE_REQUEST_WINDOW_MS, now, id, snap.token()));
    if (changed == 0) return null;
    String email = orders.invoiceCandidate(id).map(InvoiceCandidate::invoice).map(Orders.InvoiceRequest::email).orElse(null);
    CallResult response = "VOID".equals(kind) ? client.invalid(snap.number(), snap.date(), "訂單退款", timestamp)
        : client.allowance(snap.number(), snap.date(), snap.amount(), email, timestamp);
    if (response instanceof Ok ok) {
      if ("VOID".equals(kind)) return reversalDone(snap, kind, null);
      String number = Objects.toString(ok.data().get("IA_Allow_No"), "");
      return number.matches("[A-Za-z0-9_-]{1,20}") ? reversalDone(snap, kind, number) : reversalUnknown(snap);
    }
    if (response instanceof Rejected rejected) {
      if ("VOID".equals(kind)) return sendReversal(id, snap, "ALLOWANCE");
      String message = rejected.rtnMsg();
      return new ReversalOutcome("FAILED", kind, false, true, snap.attempts(), "綠界拒絕折讓（"
          + rejected.rtnCode() + "）：" + message.substring(0, Math.min(100, message.length())), null);
    }
    return reversalUnknown(snap);
  }

  private ReversalOutcome reversalDone(ReversalClaim snap, String kind, String number) {
    return new ReversalOutcome("DONE", kind, false, true, snap.attempts(), null, number);
  }

  private ReversalOutcome reversalUnknown(ReversalClaim snap) {
    int attempts = snap.attempts() + 1;
    // kind=null retains the actual last sent operation (including VOID -> ALLOWANCE fallback).
    return new ReversalOutcome(attempts >= maxAttempts ? "FAILED" : "PENDING", null, true, false,
        attempts, attempts >= maxAttempts ? "連續 " + attempts + " 次無法確認作廢／折讓結果" : "無法確認作廢／折讓結果", null);
  }
}
