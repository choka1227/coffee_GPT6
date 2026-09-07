package com.coffee.reporting.internal;

import com.coffee.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
  private final JdbcTemplate db;
  private final ZoneId zone = ZoneId.of("Asia/Taipei");

  public ReportService(JdbcTemplate db) {
    this.db = db;
  }

  record Sale(
      String id,
      String branchId,
      String branchName,
      int total,
      long paidAt,
      String fulfillment,
      String method) {}

  public Map<String, Object> report(Actor a, String month, String requestedBranch) {
    if (a.global()) a.require("REPORT_ALL");
    else a.require("REPORT_STORE");
    YearMonth m;
    try {
      m = YearMonth.parse(month);
    } catch (Exception e) {
      throw new Problem(400, "月份格式需為 YYYY-MM");
    }
    Problem.check(m.getYear() >= 2020 && m.getYear() <= 2100, "月份超出範圍");
    String branch =
        a.global()
            ? ((requestedBranch == null || requestedBranch.isBlank()) ? null : requestedBranch)
            : a.branchId();
    if (!a.global() && requestedBranch != null && !requestedBranch.isBlank())
      a.branch(requestedBranch);
    long start = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        end = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
    String filter = branch == null ? "" : " and o.branch_id=?";
    List<Object> params = new ArrayList<>(List.of(start, end));
    if (branch != null) params.add(branch);
    var sales =
        db.query(
            "select o.id,o.branch_id,b.name,o.total,o.paid_at,o.fulfillment,o.payment_method from"
                + " orders o join branches b on b.id=o.branch_id where o.paid_at>=? and o.paid_at<?"
                + filter,
            (r, n) ->
                new Sale(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getInt(4),
                    r.getLong(5),
                    r.getString(6),
                    r.getString(7)),
            params.toArray());
    long revenue = sales.stream().mapToLong(Sale::total).sum();
    long count = sales.size();
    List<Map<String, Object>> daily = new ArrayList<>();
    for (int day = 1; day <= m.lengthOfMonth(); day++) {
      final int d = day;
      var ds =
          sales.stream()
              .filter(s -> Instant.ofEpochMilli(s.paidAt()).atZone(zone).getDayOfMonth() == d)
              .toList();
      daily.add(
          Map.of(
              "day",
              String.format("%02d", day),
              "revenue",
              ds.stream().mapToLong(Sale::total).sum(),
              "orders",
              ds.size()));
    }
    var products =
        db.queryForList(
            "select i.product_id as id,i.name as name,i.category as category,sum(i.quantity) as"
                + " quantity,sum(i.unit_price*i.quantity) as revenue,sum(i.unit_cost*i.quantity) as"
                + " cost from order_items i join orders o on o.id=i.order_id where o.paid_at>=? and"
                + " o.paid_at<?"
                + filter
                + " group by i.product_id,i.name,i.category order by quantity desc,revenue desc",
            params.toArray());
    long cost = products.stream().mapToLong(p -> ((Number) p.get("cost")).longValue()).sum();
    long quantity =
        products.stream().mapToLong(p -> ((Number) p.get("quantity")).longValue()).sum();
    LocalDate today = LocalDate.now(zone);
    long ts = today.atStartOfDay(zone).toInstant().toEpochMilli(),
        te = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    List<Object> tp = new ArrayList<>(List.of(ts, te));
    if (branch != null) tp.add(branch);
    var topToday =
        db.queryForList(
            "select i.product_id as id,max(i.name) as name,sum(i.quantity) as"
                + " quantity,sum(i.unit_price*i.quantity) as revenue from order_items i join orders"
                + " o on o.id=i.order_id where o.paid_at>=? and o.paid_at<?"
                + filter
                + " group by i.product_id order by quantity desc,revenue desc limit 5",
            tp.toArray());
    var branches =
        db.queryForList(
            "select id,name,monthly_target from branches"
                + (branch == null ? "" : " where id=?")
                + " order by name",
            branch == null ? new Object[] {} : new Object[] {branch});
    List<Map<String, Object>> performance = new ArrayList<>();
    for (var b : branches) {
      var bs = sales.stream().filter(s -> s.branchId().equals(b.get("id"))).toList();
      long rev = bs.stream().mapToLong(Sale::total).sum();
      int target = ((Number) b.get("monthly_target")).intValue();
      performance.add(
          Map.of(
              "id",
              b.get("id"),
              "name",
              b.get("name"),
              "revenue",
              rev,
              "orders",
              bs.size(),
              "target",
              target,
              "achievement",
              target == 0 ? 0 : Math.round(rev * 1000.0 / target) / 10.0));
    }
    var categories = new LinkedHashMap<String, Long>();
    for (var p : products)
      categories.merge(
          (String) p.get("category"), ((Number) p.get("revenue")).longValue(), Long::sum);
    var hourly = new ArrayList<Map<String, Object>>();
    for (int h = 0; h < 24; h++) {
      final int hour = h;
      hourly.add(
          Map.of(
              "hour",
              String.format("%02d:00", h),
              "orders",
              sales.stream()
                  .filter(s -> Instant.ofEpochMilli(s.paidAt()).atZone(zone).getHour() == hour)
                  .count()));
    }
    return Map.ofEntries(
        Map.entry("month", month),
        Map.entry("today", today.toString()),
        Map.entry("revenue", revenue),
        Map.entry("orders", count),
        Map.entry("averageOrder", count == 0 ? 0 : Math.round((double) revenue / count)),
        Map.entry("quantity", quantity),
        Map.entry("grossProfit", revenue - cost),
        Map.entry(
            "grossMargin",
            revenue == 0 ? 0 : Math.round((revenue - cost) * 1000.0 / revenue) / 10.0),
        Map.entry("daily", daily),
        Map.entry("products", products),
        Map.entry("topToday", topToday),
        Map.entry("branches", performance),
        Map.entry("categories", categories),
        Map.entry("hourly", hourly),
        Map.entry("cashOrders", sales.stream().filter(s -> s.method().equals("CASH")).count()),
        Map.entry("onlineOrders", sales.stream().filter(s -> s.method().equals("ECPAY")).count()),
        Map.entry(
            "takeawayOrders",
            sales.stream().filter(s -> s.fulfillment().equals("TAKEAWAY")).count()));
  }
}
