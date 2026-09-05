package com.coffee.orders.internal;

import com.coffee.branches.api.Branches;
import com.coffee.catalog.api.Catalog;
import com.coffee.orders.api.Orders;
import com.coffee.shared.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService implements Orders {
  private final JdbcTemplate db;
  private final Catalog catalog;
  private final Branches branches;

  public OrderService(JdbcTemplate db, Catalog catalog, Branches branches) {
    this.db = db;
    this.catalog = catalog;
    this.branches = branches;
  }

  @Transactional
  public Order create(Actor a, Create q, String key) {
    a.require("ORDER_CREATE");
    Problem.check(key != null && key.matches("[A-Za-z0-9-]{16,80}"), "缺少有效的訂單重試識別碼");
    Problem.check(
        q.items() != null && !q.items().isEmpty() && q.items().size() <= 50, "請選擇 1–50 個品項");
    Problem.check(
        q.fulfillment() != null && Set.of("DINE_IN", "TAKEAWAY").contains(q.fulfillment()),
        "取餐方式不正確");
    Problem.check(
        q.paymentMethod() != null && Set.of("CASH", "ECPAY").contains(q.paymentMethod()),
        "付款方式不正確");
    Problem.check(q.note() != null && q.note().length() <= 200, "備註最多 200 字");
    String fingerprint = fingerprint(q);
    var existing =
        db.queryForList(
            "select id,request_hash from orders where account_id=? and idempotency_key=?",
            a.id(),
            key);
    if (!existing.isEmpty()) {
      Problem.check(fingerprint.equals(existing.get(0).get("request_hash")), "同一識別碼不能用於不同訂單");
      return get(a, (String) existing.get(0).get("id"));
    }
    branches.requireOpen(q.branchId());
    if (!a.customer()) {
      a.require("POS_ORDER");
      a.branch(q.branchId());
    }
    String id = Ids.order();
    List<Catalog.Product> products = new ArrayList<>();
    int total = 0;
    for (LineInput l : q.items()) {
      Problem.check(l != null && l.quantity() >= 1 && l.quantity() <= 50, "單品數量需為 1–50");
      var p = catalog.sellable(l.productId());
      validateOptions(p, l);
      products.add(p);
      total = Math.addExact(total, Math.multiplyExact(p.price(), l.quantity()));
    }
    Problem.check(total <= 1000000, "單筆訂單金額超過上限");
    db.update(
        "insert into"
            + " orders(id,branch_id,account_id,status,fulfillment,payment_method,total,note,created_at,idempotency_key,request_hash)"
            + " values(?,?,?,'PENDING_PAYMENT',?,?,?,?,?,?,?)",
        id,
        q.branchId(),
        a.id(),
        q.fulfillment(),
        q.paymentMethod(),
        total,
        q.note(),
        System.currentTimeMillis(),
        key,
        fingerprint);
    for (int i = 0; i < q.items().size(); i++) {
      var l = q.items().get(i);
      var p = products.get(i);
      db.update(
          "insert into"
              + " order_items(id,order_id,product_id,name,category,unit_price,unit_cost,quantity,temperature,sugar)"
              + " values(?,?,?,?,?,?,?,?,?,?)",
          Ids.next(),
          id,
          p.id(),
          p.name(),
          p.category(),
          p.price(),
          p.cost(),
          l.quantity(),
          l.temperature(),
          l.sugar());
    }
    return snapshot(id);
  }

  private void validateOptions(Catalog.Product p, LineInput l) {
    if (p.category().equals("手作烘焙"))
      Problem.check("不適用".equals(l.temperature()) && "不適用".equals(l.sugar()), "烘焙商品不提供冰量甜度");
    else {
      Problem.check(
          Set.of("熱", "正常冰", "少冰", "去冰").contains(l.temperature() == null ? "" : l.temperature()),
          "溫度不正確");
      Problem.check(
          Set.of("無糖", "微糖", "半糖", "正常糖").contains(l.sugar() == null ? "" : l.sugar()), "甜度不正確");
    }
  }

  private String fingerprint(Create q) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(q.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public List<Order> list(Actor a) {
    String where;
    Object[] params;
    if (a.customer()) {
      where = "account_id=?";
      params = new Object[] {a.id()};
    } else {
      a.require("ORDER_MANAGE");
      where = a.global() ? "1=1" : "branch_id=?";
      params = a.global() ? new Object[] {} : new Object[] {a.branchId()};
    }
    return db
        .queryForList(
            "select id from orders where " + where + " order by created_at desc limit 100",
            String.class,
            params)
        .stream()
        .map(this::snapshot)
        .toList();
  }

  public Order get(Actor a, String id) {
    Order o = snapshot(id);
    if (!o.accountId().equals(a.id())) {
      a.require("ORDER_MANAGE");
      a.branch(o.branchId());
    }
    return o;
  }

  private void manage(Actor a, Order o) {
    a.require("ORDER_MANAGE");
    a.branch(o.branchId());
  }

  @Transactional
  public Order cash(Actor a, String id, int tendered) {
    a.require("POS_ORDER");
    lock(id);
    Order o = snapshot(id);
    manage(a, o);
    Problem.check(o.paymentMethod().equals("CASH"), "此訂單使用線上付款");
    if (o.paidAt() != null) return o;
    Problem.check(o.status().equals("PENDING_PAYMENT"), "訂單目前無法收款");
    Problem.check(tendered >= o.total() && tendered <= 1000000, "實收金額不足或超過上限");
    db.update(
        "update orders set status='PAID',paid_at=?,tendered=?,change_amount=? where id=?",
        System.currentTimeMillis(),
        tendered,
        tendered - o.total(),
        id);
    return snapshot(id);
  }

  @Transactional
  public Order transition(Actor a, String id, String next) {
    lock(id);
    Order o = snapshot(id);
    if (a.customer()) {
      Problem.check(o.accountId().equals(a.id()), "只能取消自己的訂單");
      Problem.check("CANCELLED".equals(next), "不允許此操作");
    } else manage(a, o);
    boolean allowed =
        (o.status().equals("PENDING_PAYMENT")
                && "CANCELLED".equals(next)
                && o.paymentMethod().equals("CASH"))
            || (!a.customer()
                && Objects.equals(
                    Map.of("PAID", "PREPARING", "PREPARING", "READY", "READY", "COMPLETED")
                        .get(o.status()),
                    next));
    Problem.check(allowed, "訂單狀態已變更，或不允許此狀態轉換");
    db.update("update orders set status=? where id=?", next, id);
    return snapshot(id);
  }

  public Order payable(Actor a, String id) {
    Order o = get(a, id);
    Problem.check(
        o.paymentMethod().equals("ECPAY") && o.status().equals("PENDING_PAYMENT"), "此訂單目前無法進行線上付款");
    return o;
  }

  public Order paymentSnapshot(String id) {
    return snapshot(id);
  }

  @Transactional
  public void confirmOnline(String id, int amount, String trade) {
    lock(id);
    Order o = snapshot(id);
    Problem.check(o.paymentMethod().equals("ECPAY") && o.total() == amount, "付款金額或方式不符");
    if (o.paidAt() != null) {
      String old =
          db.queryForObject("select provider_trade_no from orders where id=?", String.class, id);
      Problem.check(Objects.equals(old, trade), "付款交易編號不符");
      return;
    }
    Problem.check(o.status().equals("PENDING_PAYMENT"), "訂單狀態無法收款");
    db.update(
        "update orders set status='PAID',paid_at=?,provider_trade_no=? where id=?",
        System.currentTimeMillis(),
        trade,
        id);
  }

  private void lock(String id) {
    if (db.queryForList("select id from orders where id=? for update", String.class, id).isEmpty())
      throw new Problem(404, "找不到訂單");
  }

  private Order snapshot(String id) {
    var rows =
        db.query(
            "select o.*,b.name branch_name from orders o join branches b on b.id=o.branch_id where"
                + " o.id=?",
            (r, n) ->
                new Order(
                    r.getString("id"),
                    r.getString("branch_id"),
                    r.getString("branch_name"),
                    r.getString("account_id"),
                    r.getString("status"),
                    r.getString("fulfillment"),
                    r.getString("payment_method"),
                    r.getInt("total"),
                    r.getString("note"),
                    r.getLong("created_at"),
                    r.getObject("paid_at", Long.class),
                    r.getObject("tendered", Integer.class),
                    r.getObject("change_amount", Integer.class),
                    db.query(
                        "select * from order_items where order_id=? order by name",
                        (x, i) ->
                            new Line(
                                x.getString("product_id"),
                                x.getString("name"),
                                x.getString("category"),
                                x.getInt("unit_price"),
                                x.getInt("quantity"),
                                x.getString("temperature"),
                                x.getString("sugar")),
                        id)),
            id);
    if (rows.isEmpty()) throw new Problem(404, "找不到訂單");
    return rows.get(0);
  }
}
