package com.coffee.payments.internal;

import com.coffee.orders.api.Orders;
import com.coffee.payments.api.*;
import com.coffee.shared.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService implements Reconciliation {
  private final Orders orders;
  private final TradeQuery query;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final String merchant, key, iv;
  private final long minAge, maxAge;
  private final int batch;
  private int cursor;
  private final Object[] locks = new Object[64];

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
      @Value("${ecpay.reconcile.batch-size:50}") int batch) {
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
    Arrays.setAll(locks, i -> new Object());
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

  public List<Pending> pending(Actor actor) {
    authorize(actor);
    long now = System.currentTimeMillis();
    List<Pending> result = new ArrayList<>();
    int offset = 0;
    while (true) {
      var page = orders.reconciliationCandidates(actor, now - maxAge, now - minAge, 200, offset);
      for (var o : page) {
        var history = attempts(o.id());
        var last = history.isEmpty() ? null : history.get(0);
        int count =
            db.queryForObject(
                "select count(*) from payment_reconciliations where order_id=?",
                Integer.class,
                o.id());
        result.add(
            new Pending(
                o.id(),
                o.branchId(),
                o.branchName(),
                o.total(),
                o.createdAt(),
                last == null ? null : last.outcome(),
                last == null ? null : last.queriedAt(),
                count));
      }
      if (page.size() < 200) return result;
      offset += page.size();
    }
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
    for (var o : candidates) {
      try {
        perform(null, o.id());
      } catch (RuntimeException e) {
        /* Isolate failures; never log payment payloads. */
      }
    }
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
      String outcome = "QUERY_FAILED", status = "unknown", trade = "", detail = "綠界查單失敗，請稍後再試";
      Integer amount = null;
      long paidAt = now;
      try {
        var p = query.query(id);
        if (!CheckMac.valid(p, key, iv)
            || !merchant.equals(p.get("MerchantID"))
            || !id.equals(p.get("MerchantTradeNo"))) throw new IllegalArgumentException();
        status = p.get("TradeStatus");
        if (status == null || !status.matches("[0-9]{1,20}")) throw new IllegalArgumentException();
        try {
          amount = Integer.valueOf(p.get("TradeAmt"));
        } catch (NumberFormatException e) {
          // Non-credit outcomes can legitimately omit an amount.
          amount = null;
        }
        trade = p.getOrDefault("TradeNo", "");
        if (!trade.isEmpty() && !trade.matches("[A-Za-z0-9]{1,64}"))
          throw new IllegalArgumentException();
        if (!"1".equals(status)) {
          outcome = "STILL_UNPAID";
          detail = "綠界尚未確認付款";
        } else if ("1".equals(p.get("SimulatePaid"))) {
          outcome = "SIMULATED";
          detail = "綠界模擬付款，不予入帳";
        } else if (amount == null) {
          throw new IllegalArgumentException();
        } else if (amount != o.total()) {
          outcome = "AMOUNT_MISMATCH";
          detail = "綠界金額 " + amount + " 元與訂單 " + o.total() + " 元不符";
        } else {
          if (trade.isEmpty()) throw new IllegalArgumentException();
          outcome = "CONFIRMED";
          detail = "綠界回報已付款，訂單已更新為已付款";
          try {
            paidAt =
                LocalDateTime.parse(
                        p.getOrDefault("PaymentDate", ""),
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
        detail = "綠界查單驗證或連線失敗，訂單未變更";
      }
      final String result = outcome, tradeStatus = status, tradeNo = trade, message = detail;
      final Integer tradeAmount = amount;
      final long paymentTime = paidAt;
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
