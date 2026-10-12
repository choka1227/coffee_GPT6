package com.coffee.payments.internal;

import com.coffee.orders.api.Orders;
import com.coffee.payments.api.*;
import com.coffee.shared.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService implements Reconciliation {
  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
  private final Orders orders;
  private final TradeQuery query;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final String merchant, key, iv;
  private final long minAge, maxAge;
  private final long expireAfter, recheckAfter;
  private final int batch;
  private final boolean expireEnabled;
  private final PaymentAnomalies anomalies;
  private int cursor;
  private int expireCursor;
  private final Object[] locks = new Object[64];

  @Autowired
  public ReconciliationService(
      Orders orders,
      TradeQuery query,
      JdbcTemplate db,
      PlatformTransactionManager transactions,
      @Value("${ecpay.reconcile.enabled:false}") boolean enabled,
      @Value("${ecpay.enabled:false}") boolean paymentEnabled,
      @Value("${ecpay.merchant-id:}") String merchant,
      @Value("${ecpay.hash-key:}") String key,
      @Value("${ecpay.hash-iv:}") String iv,
      @Value("${ecpay.reconcile.min-age-minutes:15}") long minMinutes,
      @Value("${ecpay.reconcile.max-age-days:7}") long maxDays,
      @Value("${ecpay.reconcile.batch-size:50}") int batch,
      @Value("${ecpay.expire.enabled:false}") boolean expireEnabled,
      @Value("${ecpay.expire.checkout-window-minutes:20}") long checkoutWindowMinutes,
      @Value("${ecpay.expire.after-minutes:60}") long expireAfterMinutes,
      @Value("${ecpay.expire.recheck-after-minutes:30}") long recheckAfterMinutes,
      @Value("${ecpay.expire.interval-ms:300000}") long expireIntervalMs) {
    Problem.check(!enabled || paymentEnabled, "啟用對帳前必須先啟用綠界金流");
    Problem.check(
        minMinutes >= 0
            && minMinutes <= 10080
            && maxDays >= 1
            && maxDays <= 365
            && minMinutes < maxDays * 1440
            && batch >= 1
            && batch <= 1000,
        "對帳排程設定不正確");
    if (expireEnabled && !enabled)
      throw new IllegalStateException("啟用逾時作廢前必須先啟用金流對帳");
    if (expireEnabled
        && !(checkoutWindowMinutes >= 5
            && checkoutWindowMinutes <= 1440
            && expireAfterMinutes >= checkoutWindowMinutes + 30
            && expireAfterMinutes > minMinutes
            && expireAfterMinutes <= 10080
            && recheckAfterMinutes >= 5
            && recheckAfterMinutes <= 1440
            && expireIntervalMs >= 60000
            && expireIntervalMs <= 86400000))
      throw new IllegalStateException("逾時作廢設定不正確");
    this.orders = orders;
    this.query = query;
    this.db = db;
    this.tx = new TransactionTemplate(transactions);
    this.enabled = enabled;
    this.merchant = merchant;
    this.key = key;
    this.iv = iv;
    this.minAge = minMinutes * 60000;
    this.maxAge = maxDays * 86400000;
    this.batch = batch;
    this.expireEnabled = expireEnabled;
    this.expireAfter = expireAfterMinutes * 60000;
    this.recheckAfter = recheckAfterMinutes * 60000;
    this.anomalies = new PaymentAnomalies(db);
    Arrays.setAll(locks, i -> new Object());
  }

  public ReconciliationService(
      Orders orders,
      TradeQuery query,
      JdbcTemplate db,
      PlatformTransactionManager transactions,
      boolean enabled,
      boolean paymentEnabled,
      String merchant,
      String key,
      String iv,
      long minMinutes,
      long maxDays,
      int batch) {
    this(
        orders, query, db, transactions, enabled, paymentEnabled, merchant, key, iv, minMinutes,
        maxDays, batch, false, 20, 60, 30, 300000);
  }

  private void authorize(Actor actor) {
    if (actor.customer() || !actor.can("PAYMENT_RECONCILE")) throw new Problem(403, "沒有執行金流對帳的權限");
    if (!enabled) throw new Problem(503, "金流對帳功能尚未啟用");
  }

  private Orders.Order order(Actor actor, String id) {
    authorize(actor);
    if (id == null || !id.matches("[A-Za-z0-9]{20}")) throw new Problem(400, "訂單編號格式不正確");
    Orders.Order o;
    try {
      o = orders.paymentSnapshot(id);
    } catch (Problem e) {
      if (e.status == 404) throw new Problem(404, "找不到這筆訂單");
      throw e;
    }
    if (!actor.global() && !Objects.equals(actor.branchId(), o.branchId()))
      throw new Problem(403, "沒有查看其他分店訂單的權限");
    return o;
  }

  private record PendingSummary(String lastOutcome, Long lastQueriedAt, int attempts) {}

  public PendingPage pending(Actor actor) {
    authorize(actor);
    long now = System.currentTimeMillis();
    var candidates = orders.reconciliationCandidates(actor, now - maxAge, now - minAge, 201, 0);
    boolean truncated = candidates.size() > 200;
    var visible = candidates.stream().limit(200).toList();
    var summaries = pendingSummaries(visible.stream().map(Orders.Order::id).toList());
    var items =
        visible.stream()
            .map(
                o -> {
                  var summary = summaries.get(o.id());
                  return new Pending(
                      o.id(),
                      o.branchId(),
                      o.branchName(),
                      o.total(),
                      o.createdAt(),
                      summary == null ? null : summary.lastOutcome(),
                      summary == null ? null : summary.lastQueriedAt(),
                      summary == null ? 0 : summary.attempts());
                })
            .toList();
    return new PendingPage(items, truncated);
  }

  private Map<String, PendingSummary> pendingSummaries(List<String> orderIds) {
    // Keep the number of database round trips constant even when there are no candidates.
    var queryIds = orderIds.isEmpty() ? List.of("") : orderIds;
    String placeholders = String.join(",", Collections.nCopies(queryIds.size(), "?"));
    return db.query(
        "select order_id,max(case when rn=1 then outcome end) last_outcome,"
            + " max(queried_at) last_queried_at,count(*) attempts from ("
            + " select order_id,outcome,queried_at,row_number() over (partition by order_id"
            + " order by queried_at desc,id desc) rn from payment_reconciliations"
            + " where order_id in ("
            + placeholders
            + ")) ranked group by order_id",
        rs -> {
          Map<String, PendingSummary> result = new HashMap<>();
          while (rs.next())
            result.put(
                rs.getString("order_id"),
                new PendingSummary(
                    rs.getString("last_outcome"),
                    rs.getObject("last_queried_at", Long.class),
                    rs.getInt("attempts")));
          return result;
        },
        queryIds.toArray());
  }

  private List<Attempt> attempts(String id) {
    return db.query(
        "select * from payment_reconciliations where order_id=? order by queried_at desc,id desc"
            + " limit 50",
        (r, n) ->
            new Attempt(
                r.getString("outcome"),
                r.getString("trigger_source"),
                r.getString("trade_status"),
                r.getObject("trade_amount", Integer.class),
                r.getString("detail"),
                r.getLong("queried_at")),
        id);
  }

  public List<Attempt> history(Actor actor, String id) {
    order(actor, id);
    return attempts(id);
  }

  public Result reconcile(Actor actor, String id) {
    order(actor, id);
    return perform(actor, id);
  }

  @Scheduled(
      fixedDelayString = "${ecpay.reconcile.interval-ms:300000}",
      initialDelayString = "${ecpay.reconcile.interval-ms:300000}")
  public synchronized void scheduled() {
    if (!enabled) return;
    long now = System.currentTimeMillis();
    var candidates =
        orders.reconciliationCandidates(null, now - maxAge, now - minAge, batch, cursor);
    // Rotate through the whole window; old unpaid orders must not starve newer ones.
    cursor = candidates.size() < batch ? 0 : cursor + candidates.size();
    Map<String, Integer> outcomes = new HashMap<>();
    int skipped = 0;
    for (var o : candidates) {
      try {
        var result = perform(null, o.id());
        outcomes.merge(result.outcome(), 1, Integer::sum);
      } catch (RuntimeException e) {
        skipped++;
        log.warn("綠界排程對帳單筆失敗：訂單 {}，{}", o.id(), e.getClass().getSimpleName());
      }
    }
    log.info(
        "綠界排程對帳完成：候選 {} 筆，CONFIRMED {}、STILL_UNPAID {}、SIMULATED {}、AMOUNT_MISMATCH {}、QUERY_FAILED {}、略過 {}",
        candidates.size(),
        outcomes.getOrDefault("CONFIRMED", 0),
        outcomes.getOrDefault("STILL_UNPAID", 0),
        outcomes.getOrDefault("SIMULATED", 0),
        outcomes.getOrDefault("AMOUNT_MISMATCH", 0),
        outcomes.getOrDefault("QUERY_FAILED", 0),
        skipped);
  }

  @Scheduled(
      fixedDelayString = "${ecpay.expire.interval-ms:300000}",
      initialDelayString = "${ecpay.expire.interval-ms:300000}")
  public synchronized void expireScheduled() {
    if (!expireEnabled) return;
    long now = System.currentTimeMillis();
    var candidates =
        orders.reconciliationCandidates(null, 0, now - expireAfter, batch, expireCursor);
    expireCursor = candidates.size() < batch ? 0 : expireCursor + candidates.size();
    Map<String, Integer> outcomes = new HashMap<>();
    int skipped = 0;
    for (var order : candidates) {
      try {
        outcomes.merge(expireOne(order.id()), 1, Integer::sum);
      } catch (RuntimeException e) {
        skipped++;
        log.warn("綠界逾時作廢單筆失敗：訂單 {}，{}", order.id(), e.getClass().getSimpleName());
      }
    }
    log.info(
        "綠界逾時作廢完成：候選 {} 筆，作廢 {}、已付款 {}、保留 {}、略過 {}",
        candidates.size(),
        outcomes.getOrDefault("EXPIRED", 0),
        outcomes.getOrDefault("PAID", 0),
        outcomes.getOrDefault("KEPT", 0),
        skipped);
    recheck(now);
  }

  private String expireOne(String id) {
    synchronized (locks[Math.floorMod(id.hashCode(), locks.length)]) {
      Result result = perform(null, id);
      if (result.outcome().equals("CONFIRMED")) return "PAID";
      if (!Set.of("STILL_UNPAID", "SIMULATED").contains(result.outcome())) return "KEPT";
      boolean expired =
          Boolean.TRUE.equals(
              tx.execute(
                  status -> {
                    if (!orders.expireOnline(id)) return false;
                    db.update(
                        "insert into payment_expiries(order_id,expired_at) values(?,?)",
                        id,
                        System.currentTimeMillis());
                    return true;
                  }));
      return expired ? "EXPIRED" : "KEPT";
    }
  }

  private record Expired(String orderId, long expiredAt) {}

  private void recheck(long now) {
    var candidates =
        db.query(
            "select order_id,expired_at from payment_expiries"
                + " where rechecked_at is null and expired_at<=?"
                + " order by expired_at,order_id limit ?",
            (row, number) -> new Expired(row.getString("order_id"), row.getLong("expired_at")),
            now - recheckAfter,
            batch);
    Map<String, Integer> outcomes = new HashMap<>();
    for (var expired : candidates) {
      try {
        String outcome = recheckOne(expired, now);
        outcomes.merge(outcome, 1, Integer::sum);
      } catch (RuntimeException e) {
        outcomes.merge("QUERY_FAILED", 1, Integer::sum);
        log.warn(
            "綠界作廢後複查單筆失敗：訂單 {}，{}", expired.orderId(), e.getClass().getSimpleName());
      }
    }
    log.info(
        "綠界作廢後複查完成：候選 {} 筆，未付款 {}、已付款 {}、模擬 {}、查單失敗 {}、放棄 {}",
        candidates.size(),
        outcomes.getOrDefault("UNPAID", 0),
        outcomes.getOrDefault("PAID", 0),
        outcomes.getOrDefault("SIMULATED", 0),
        outcomes.getOrDefault("QUERY_FAILED", 0),
        outcomes.getOrDefault("GAVE_UP", 0));
  }

  private String recheckOne(Expired expired, long now) {
    synchronized (locks[Math.floorMod(expired.orderId().hashCode(), locks.length)]) {
      var order = orders.paymentSnapshot(expired.orderId());
      Verification result = verify(order.id(), order.total());
      return switch (result.outcome()) {
        case "STILL_UNPAID" -> {
          finishRecheck(order.id(), "UNPAID", now);
          yield "UNPAID";
        }
        case "SIMULATED" -> {
          finishRecheck(order.id(), "SIMULATED", now);
          yield "SIMULATED";
        }
        case "CONFIRMED", "AMOUNT_MISMATCH" -> {
          tx.executeWithoutResult(
              status -> {
                anomalies.record(order, "RECHECK", result.tradeNo(), result.amount());
                finishRecheck(order.id(), "PAID", now);
              });
          yield "PAID";
        }
        default -> {
          db.update(
              "update payment_expiries set recheck_attempts=recheck_attempts+1 where order_id=?",
              order.id());
          if (expired.expiredAt() < now - maxAge) {
            finishRecheck(order.id(), "GAVE_UP", now);
            log.warn("綠界作廢後複查放棄：訂單 {}", order.id());
            yield "GAVE_UP";
          }
          yield "QUERY_FAILED";
        }
      };
    }
  }

  private void finishRecheck(String id, String outcome, long now) {
    db.update(
        "update payment_expiries set rechecked_at=?,recheck_outcome=? where order_id=?",
        now,
        outcome,
        id);
  }

  private record Verification(
      String outcome,
      String tradeStatus,
      Integer amount,
      String tradeNo,
      long paidAt,
      String detail) {}

  private Verification verify(String id, int expectedTotal) {
    long now = System.currentTimeMillis();
    String outcome = "QUERY_FAILED", status = "unknown", trade = "";
    String detail = "綠界查單失敗，請稍後再試";
    Integer amount = null;
    long paidAt = now;
    try {
      var response = query.query(id);
      if (!CheckMac.valid(response, key, iv)
          || !merchant.equals(response.get("MerchantID"))
          || !id.equals(response.get("MerchantTradeNo"))) throw new IllegalArgumentException();
      status = response.get("TradeStatus");
      if (status == null || !status.matches("[0-9]{1,20}")) throw new IllegalArgumentException();
      try {
        amount = Integer.valueOf(response.get("TradeAmt"));
      } catch (NumberFormatException e) {
        amount = null;
      }
      trade = response.getOrDefault("TradeNo", "");
      if (!trade.isEmpty() && !trade.matches("[A-Za-z0-9]{1,64}"))
        throw new IllegalArgumentException();
      if (!"1".equals(status)) {
        outcome = "STILL_UNPAID";
        detail = "綠界尚未確認付款";
      } else if ("1".equals(response.get("SimulatePaid"))) {
        outcome = "SIMULATED";
        detail = "綠界模擬付款，不予入帳";
      } else if (amount == null) {
        throw new IllegalArgumentException();
      } else if (amount != expectedTotal) {
        outcome = "AMOUNT_MISMATCH";
        detail = "綠界金額 " + amount + " 元與訂單 " + expectedTotal + " 元不符";
      } else {
        if (trade.isEmpty()) throw new IllegalArgumentException();
        outcome = "CONFIRMED";
        detail = "綠界回報已付款，訂單已更新為已付款";
        try {
          paidAt =
              LocalDateTime.parse(
                      response.getOrDefault("PaymentDate", ""),
                      DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm:ss")
                          .withResolverStyle(ResolverStyle.STRICT))
                  .atZone(ZoneId.of("Asia/Taipei"))
                  .toInstant()
                  .toEpochMilli();
          if (paidAt <= 0 || paidAt > now) {
            paidAt = now;
            detail = "綠界付款時間超出有效範圍，以查核時間入帳";
          }
        } catch (DateTimeParseException e) {
          paidAt = now;
          detail = "綠界未提供付款時間，以查核時間入帳";
        }
      }
    } catch (RuntimeException e) {
      outcome = "QUERY_FAILED";
      status = "unknown";
      amount = null;
      trade = "";
      detail = "綠界查單失敗，請稍後再試";
    }
    return new Verification(outcome, status, amount, trade, paidAt, detail);
  }

  private Result perform(Actor actor, String id) {
    synchronized (locks[Math.floorMod(id.hashCode(), locks.length)]) {
      var o = actor == null ? orders.paymentSnapshot(id) : order(actor, id);
      if (!o.paymentMethod().equals("ECPAY") || !o.status().equals("PENDING_PAYMENT"))
        throw new Problem(409, "這筆訂單不需要對帳");
      long now = System.currentTimeMillis();
      if (actor != null) {
        Long last =
            db.queryForObject(
                "select max(queried_at) from payment_reconciliations where order_id=? and"
                    + " trigger_source='MANUAL'",
                Long.class,
                id);
        if (last != null && now - last < 60000) throw new Problem(429, "查核過於頻繁，請稍後再試");
      }
      Verification verification = verify(id, o.total());
      final String result = verification.outcome();
      final String tradeStatus = verification.tradeStatus();
      final String tradeNo = verification.tradeNo();
      final String message = verification.detail();
      final Integer tradeAmount = verification.amount();
      final long paymentTime = verification.paidAt();
      try {
        tx.executeWithoutResult(
            t -> {
              if (result.equals("CONFIRMED"))
                orders.confirmOnline(id, tradeAmount, tradeNo, paymentTime);
              save(actor, id, result, tradeStatus, tradeAmount, tradeNo, message, now);
            });
        return new Result(id, result, message, now);
      } catch (RuntimeException e) {
        // The failed transaction rolled back the order and audit together.
        String failure = "入帳時訂單狀態或交易資料衝突，請人工查核";
        tx.executeWithoutResult(
            t -> save(actor, id, "QUERY_FAILED", "unknown", null, "", failure, now));
        return new Result(id, "QUERY_FAILED", failure, now);
      }
    }
  }

  private void save(
      Actor actor,
      String id,
      String result,
      String status,
      Integer amount,
      String trade,
      String detail,
      long now) {
    db.update(
        "insert into"
            + " payment_reconciliations(id,order_id,trigger_source,actor_id,outcome,trade_status,trade_amount,provider_trade_no,detail,queried_at)"
            + " values(?,?,?,?,?,?,?,?,?,?)",
        Ids.next(),
        id,
        actor == null ? "SCHEDULED" : "MANUAL",
        actor == null ? null : actor.id(),
        result,
        status,
        amount,
        trade,
        detail,
        now);
  }
}
