package com.coffee.orders.internal;

import com.coffee.audit.api.Audit;
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
  private static final Set<String> ORDER_STATUSES =
      Set.of("PENDING_PAYMENT", "PAID", "PREPARING", "READY", "COMPLETED", "CANCELLED");
  private final JdbcTemplate db;
  private final Catalog catalog;
  private final Branches branches;
  private final Audit audit;

  public OrderService(JdbcTemplate db, Catalog catalog, Branches branches, Audit audit) {
    this.db = db;
    this.catalog = catalog;
    this.branches = branches;
    this.audit = audit;
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
    q = normalize(q);
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
    List<List<Catalog.ResolvedOption>> resolved = new ArrayList<>();
    int total = 0;
    for (LineInput l : q.items()) {
      Problem.check(l != null && l.quantity() >= 1 && l.quantity() <= 50, "單品數量需為 1–50");
      var p = catalog.sellable(q.branchId(), l.productId());
      var options = catalog.resolveOptions(p.id(), l.optionIds());
      int optionPrice = options.stream().mapToInt(Catalog.ResolvedOption::priceDelta).sum();
      int unitPrice = Math.addExact(p.price(), optionPrice);
      Problem.check(unitPrice > 0, "商品金額不正確");
      products.add(p);
      resolved.add(options);
      total = Math.addExact(total, Math.multiplyExact(unitPrice, l.quantity()));
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
      var options = resolved.get(i);
      int optionsPrice = options.stream().mapToInt(Catalog.ResolvedOption::priceDelta).sum();
      int optionsCost = options.stream().mapToInt(Catalog.ResolvedOption::costDelta).sum();
      String itemId = Ids.next();
      db.update(
          "insert into"
              + " order_items(id,order_id,product_id,name,category,unit_price,unit_cost,quantity,temperature,sugar,options_price,options_cost)"
              + " values(?,?,?,?,?,?,?,?,null,null,?,?)",
          itemId,
          id,
          p.id(),
          p.name(),
          p.category(),
          p.price(),
          p.cost(),
          l.quantity(),
          optionsPrice,
          optionsCost);
      for (var option : options)
        db.update(
            "insert into order_item_options(id,order_item_id,group_id,group_name,option_id,option_name,price_delta,cost_delta) values(?,?,?,?,?,?,?,?)",
            Ids.next(), itemId, option.groupId(), option.groupName(), option.optionId(),
            option.optionName(), option.priceDelta(), option.costDelta());
    }
    return snapshot(id);
  }

  private Create normalize(Create q) {
    if (q.items() == null) return q;
    return new Create(
        q.branchId(), q.fulfillment(), q.paymentMethod(), q.note(),
        q.items().stream()
            .map(l -> l == null ? null : new LineInput(
                l.productId(), l.quantity(),
                l.optionIds() == null ? null : l.optionIds().stream().sorted().toList()))
            .toList());
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

  public Page page(Actor a, Query query) {
    Query q = query == null ? new Query(null, null, null, null, null, null, 0) : query;
    Problem.check(q.status() == null || ORDER_STATUSES.contains(q.status()), "訂單狀態不正確");
    Problem.check(q.from() == null || q.to() == null || q.from() <= q.to(), "查詢時間區間不正確");
    String keyword = q.q() == null ? null : q.q().trim();
    if (keyword != null && keyword.isEmpty()) keyword = null;
    Problem.check(keyword == null || keyword.length() <= 60, "搜尋關鍵字過長");
    Cursor cursor = q.cursor() == null ? null : decodeCursor(q.cursor());
    int limit = q.limit() < 1 ? 50 : Math.min(q.limit(), 200);

    StringBuilder sql =
        new StringBuilder(
            "select o.*,b.name branch_name from orders o join branches b on b.id=o.branch_id where 1=1");
    List<Object> params = new ArrayList<>();
    appendScope(a, q.branchId(), sql, params);
    if (q.status() != null) {
      sql.append(" and o.status=?");
      params.add(q.status());
    }
    if (q.from() != null) {
      sql.append(" and o.created_at>=?");
      params.add(q.from());
    }
    if (q.to() != null) {
      sql.append(" and o.created_at<=?");
      params.add(q.to());
    }
    if (keyword != null) {
      String pattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
      sql.append(
          " and (lower(o.id) like ? escape '\\' or exists (select 1 from order_items i"
              + " where i.order_id=o.id and lower(i.name) like ? escape '\\'))");
      params.add(pattern);
      params.add(pattern);
    }
    if (cursor != null) {
      sql.append(" and (o.created_at<? or (o.created_at=? and o.id<?))");
      params.add(cursor.createdAt());
      params.add(cursor.createdAt());
      params.add(cursor.id());
    }
    sql.append(" order by o.created_at desc,o.id desc limit ?");
    params.add(limit + 1);

    List<OrderHeader> headers =
        db.query(
            sql.toString(),
            (r, n) ->
                new OrderHeader(
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
                    r.getObject("change_amount", Integer.class)),
            params.toArray());
    boolean more = headers.size() > limit;
    if (more) headers = new ArrayList<>(headers.subList(0, limit));
    if (headers.isEmpty()) return new Page(List.of(), null);

    Map<String, List<LineRow>> lineRows = loadLines(headers);
    List<String> itemIds =
        lineRows.values().stream().flatMap(Collection::stream).map(LineRow::id).toList();
    Map<String, List<LineOption>> options = itemIds.isEmpty() ? Map.of() : loadOptions(itemIds);
    List<Order> orders =
        headers.stream()
            .map(h -> h.toOrder(toLines(lineRows.getOrDefault(h.id(), List.of()), options)))
            .toList();
    OrderHeader last = headers.get(headers.size() - 1);
    return new Page(orders, more ? encodeCursor(last.createdAt(), last.id()) : null);
  }

  private void appendScope(Actor a, String branchId, StringBuilder sql, List<Object> params) {
    if (a.customer()) {
      sql.append(" and o.account_id=?");
      params.add(a.id());
      if (branchId != null) {
        sql.append(" and o.branch_id=?");
        params.add(branchId);
      }
      return;
    }
    a.require("ORDER_MANAGE");
    if (a.global()) {
      if (branchId != null) {
        sql.append(" and o.branch_id=?");
        params.add(branchId);
      }
    } else {
      if (branchId != null) a.branch(branchId);
      sql.append(" and o.branch_id=?");
      params.add(a.branchId());
    }
  }

  private Map<String, List<LineRow>> loadLines(List<OrderHeader> headers) {
    List<String> orderIds = headers.stream().map(OrderHeader::id).toList();
    String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
    Map<String, List<LineRow>> lines = new LinkedHashMap<>();
    db.query(
        "select * from order_items where order_id in (" + placeholders
            + ") order by order_id,name",
        r -> {
          LineRow line =
              new LineRow(
                  r.getString("id"),
                  r.getString("order_id"),
                  r.getString("product_id"),
                  r.getString("name"),
                  r.getString("category"),
                  r.getInt("unit_price"),
                  r.getInt("quantity"),
                  r.getString("temperature"),
                  r.getString("sugar"),
                  r.getInt("options_price"));
          lines.computeIfAbsent(line.orderId(), ignored -> new ArrayList<>()).add(line);
        },
        orderIds.toArray());
    return lines;
  }

  private Map<String, List<LineOption>> loadOptions(List<String> itemIds) {
    String placeholders = String.join(",", Collections.nCopies(itemIds.size(), "?"));
    Map<String, List<LineOption>> options = new HashMap<>();
    db.query(
        "select order_item_id,group_name,option_name,price_delta from order_item_options"
            + " where order_item_id in (" + placeholders + ") order by order_item_id,id",
        r -> {
            options
                .computeIfAbsent(r.getString("order_item_id"), ignored -> new ArrayList<>())
                .add(
                    new LineOption(
                        r.getString("group_name"),
                        r.getString("option_name"),
                        r.getInt("price_delta")));
        },
        itemIds.toArray());
    return options;
  }

  private List<Line> toLines(
      List<LineRow> rows, Map<String, List<LineOption>> optionsByItem) {
    return rows.stream()
        .map(
            row ->
                new Line(
                    row.productId(),
                    row.name(),
                    row.category(),
                    row.unitPrice(),
                    row.quantity(),
                    row.temperature(),
                    row.sugar(),
                    row.optionsPrice(),
                    Math.multiplyExact(
                        Math.addExact(row.unitPrice(), row.optionsPrice()), row.quantity()),
                    optionsByItem.getOrDefault(row.id(), List.of())))
        .toList();
  }

  static String encodeCursor(long createdAt, String id) {
    return createdAt + ":" + id;
  }

  static Cursor decodeCursor(String value) {
    int separator = value.indexOf(':');
    Problem.check(separator > 0 && separator < value.length() - 1, "查詢游標格式不正確");
    String time = value.substring(0, separator);
    String id = value.substring(separator + 1);
    Problem.check(time.chars().allMatch(Character::isDigit) && id.length() <= 20, "查詢游標格式不正確");
    try {
      return new Cursor(Long.parseLong(time), id);
    } catch (NumberFormatException ignored) {
      throw new Problem(400, "查詢游標格式不正確");
    }
  }

  static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  record Cursor(long createdAt, String id) {}

  private record OrderHeader(
      String id,
      String branchId,
      String branchName,
      String accountId,
      String status,
      String fulfillment,
      String paymentMethod,
      int total,
      String note,
      long createdAt,
      Long paidAt,
      Integer tendered,
      Integer changeAmount) {
    Order toOrder(List<Line> lines) {
      return new Order(
          id, branchId, branchName, accountId, status, fulfillment, paymentMethod, total, note,
          createdAt, paidAt, tendered, changeAmount, lines);
    }
  }

  private record LineRow(
      String id,
      String orderId,
      String productId,
      String name,
      String category,
      int unitPrice,
      int quantity,
      String temperature,
      String sugar,
      int optionsPrice) {}

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
    String branchId = branchOf(id);
    a.branch(branchId);
    lockBranch(branchId);
    lock(id);
    Order o = snapshot(id);
    manage(a, o);
    Problem.check(o.paymentMethod().equals("CASH"), "此訂單使用線上付款");
    if (o.paidAt() != null) return o;
    Problem.check(o.status().equals("PENDING_PAYMENT"), "訂單目前無法收款");
    Problem.check(tendered >= o.total() && tendered <= 1000000, "實收金額不足或超過上限");
    String cashSessionId =
        db.query(
                "select id from cash_sessions where branch_id=? and status='OPEN'",
                (r, n) -> r.getString("id"),
                branchId)
            .stream()
            .findFirst()
            .orElse(null);
    db.update(
        "update orders set status='PAID',paid_at=?,tendered=?,change_amount=?,cash_session_id=?"
            + " where id=?",
        System.currentTimeMillis(),
        tendered,
        tendered - o.total(),
        cashSessionId,
        id);
    audit.record(
        a,
        "ORDER_CASH",
        id,
        o.branchId(),
        "現金收款 " + o.total() + " 元，實收 " + tendered + " 元，找零 "
            + (tendered - o.total()) + " 元");
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
    audit.record(
        a,
        "ORDER_TRANSITION",
        id,
        o.branchId(),
        "訂單狀態 " + o.status() + " → " + next);
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
    confirmOnline(id, amount, trade, System.currentTimeMillis());
  }

  public List<Order> reconciliationCandidates(
      Actor actor, long since, long until, int limit, int offset) {
    String scope = "";
    List<Object> params = new ArrayList<>(List.of(since, until));
    if (actor != null) {
      actor.require("PAYMENT_RECONCILE");
      if (actor.customer()) throw new Problem(403, "沒有執行金流對帳的權限");
      if (!actor.global()) {
        scope = " and o.branch_id=?";
        params.add(actor.branchId());
      }
    }
    params.add(limit);
    params.add(offset);
    return db.query(
        "select o.*,b.name branch_name from orders o join branches b on b.id=o.branch_id"
            + " where o.payment_method='ECPAY' and o.status='PENDING_PAYMENT'"
            + " and o.created_at>=? and o.created_at<=?"
            + scope
            + " order by o.created_at,o.id limit ? offset ?",
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
                List.of()),
        params.toArray());
  }

  @Transactional
  public void confirmOnline(String id, int amount, String trade, long paidAt) {
    Problem.check(paidAt > 0 && paidAt <= System.currentTimeMillis(), "付款時間不正確");
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
        paidAt,
        trade,
        id);
  }

  private void lock(String id) {
    if (db.queryForList("select id from orders where id=? for update", String.class, id).isEmpty())
      throw new Problem(404, "找不到訂單");
  }

  private String branchOf(String id) {
    var rows = db.queryForList("select branch_id from orders where id=?", String.class, id);
    if (rows.isEmpty()) throw new Problem(404, "找不到訂單");
    return rows.get(0);
  }

  private void lockBranch(String branchId) {
    if (db.queryForList("select id from branches where id=? for update", String.class, branchId)
        .isEmpty()) throw new Problem(404, "找不到分店");
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
                                x.getString("sugar"),
                                x.getInt("options_price"),
                                Math.multiplyExact(
                                    Math.addExact(x.getInt("unit_price"), x.getInt("options_price")),
                                    x.getInt("quantity")),
                                db.query(
                                    "select group_name,option_name,price_delta from order_item_options where order_item_id=? order by id",
                                    (z, j) -> new LineOption(
                                        z.getString("group_name"), z.getString("option_name"),
                                        z.getInt("price_delta")),
                                    x.getString("id"))),
                        id)),
            id);
    if (rows.isEmpty()) throw new Problem(404, "找不到訂單");
    return rows.get(0);
  }
}
