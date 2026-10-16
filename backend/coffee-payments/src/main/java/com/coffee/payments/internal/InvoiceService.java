package com.coffee.payments.internal;

import com.coffee.orders.api.Orders;
import com.coffee.orders.api.Orders.InvoiceCandidate;
import com.coffee.payments.api.Invoices;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import com.coffee.payments.internal.EcpayInvoiceClient.*;
import java.util.*;
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

  public record Claim(String token, int amount, int attempts, boolean uncertain, Long inflightUntil) {}
  private record Outcome(String status, boolean uncertain, boolean clearBound, int attempts,
                         String error, String number, String date, String random) {}

  private Invoice readInvoice(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new Invoice(row.getString("order_id"), row.getString("branch_id"), row.getInt("amount"),
        row.getString("status"), row.getString("invoice_no"), row.getString("invoice_date"),
        row.getString("random_number"), row.getInt("attempts"), row.getString("last_error"), row.getLong("updated_at"));
  }

  @Override
  public Optional<Invoice> forOrder(Actor actor, String orderId) {
    orders.get(actor, orderId);
    return db.query("select * from invoices where order_id=?", this::readInvoice, orderId).stream().findFirst();
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
        ? db.query("select * from invoices where status='FAILED' order by updated_at desc,order_id limit 201", this::readInvoice)
        : db.query("select * from invoices where status='FAILED' and branch_id=? order by updated_at desc,order_id limit 201", this::readInvoice, branch);
    return new InvoicePage(List.copyOf(rows.subList(0, Math.min(200, rows.size()))), rows.size() > 200);
  }

  @Override
  public Invoice retry(Actor actor, String orderId) {
    requireStaff(actor);
    return tx.execute(ignored -> {
      var invoice = db.query("select * from invoices where order_id=?", this::readInvoice, orderId)
          .stream().findFirst().orElseThrow(() -> new Problem(404, "找不到這筆發票"));
      actor.branch(invoice.branchId());
      if (db.update("update invoices set status='PENDING',attempts=0,uncertain=true,claim_token=null,"
          + "claimed_until=null,last_error=null,updated_at=? where order_id=? and status='FAILED'",
          System.currentTimeMillis(), orderId) == 0)
        throw new Problem(409, "這張發票目前不需要重試");
      return db.queryForObject("select * from invoices where order_id=?", this::readInvoice, orderId);
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
    if (queued + issued + failed + pending != 0)
      log.info("綠界發票：入列 {}、開立 {}、失敗 {}、待確認 {}", queued, issued, failed, pending);
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
        tx.executeWithoutResult(status -> db.update("update invoices set status='FAILED',uncertain=false,"
            + "last_error='訂單缺少發票資訊',updated_at=? where order_id=? and status='PENDING' "
            + "and claim_token is null and inflight_until is null", System.currentTimeMillis(), orderId));
        return;
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
      int changed = db.update("update invoices set status=?,uncertain=?,attempts=?,last_error=?,"
          + "invoice_no=?,invoice_date=?,random_number=?,issued_at=?,"
          + "inflight_until=case when ? then null else inflight_until end,"
          + "claim_token=null,claimed_until=null,updated_at=? where order_id=? and claim_token=?",
          result.status(), result.uncertain(), result.attempts(), result.error(), result.number(),
          result.date(), result.random(), "ISSUED".equals(result.status()) ? now : null,
          result.clearBound(), now, orderId, snap.token());
      if (changed == 0 && "ISSUED".equals(result.status())) {
        db.update("update invoices set status='PENDING',uncertain=true,claim_token=null,claimed_until=null,"
            + "last_error='收到遲到的開立回應，待確認',updated_at=? where order_id=? and status in ('FAILED','SKIPPED')", now, orderId);
        db.update("update invoices set uncertain=true where order_id=? and status='PENDING'", orderId);
        log.warn("綠界發票：orderId={}、遲到的開立回應", orderId);
      }
    });
  }
}
