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
    var dailyRows =
        db.query(
            "select (o.paid_at+28800000)/86400000 as taipei_day,sum(o.total) as revenue,count(*) as"
                + " orders from orders o where o.paid_at>=? and o.paid_at<?"
                + filter
                + " group by (o.paid_at+28800000)/86400000",
            (r, n) -> new long[] {r.getLong(1), r.getLong(2), r.getLong(3)},
            params.toArray());
    long[] dailyRevenue = new long[m.lengthOfMonth()];
    int[] dailyOrders = new int[m.lengthOfMonth()];
    for (long[] row : dailyRows) {
      int day = LocalDate.ofEpochDay(row[0]).getDayOfMonth();
      dailyRevenue[day - 1] = row[1];
      dailyOrders[day - 1] = Math.toIntExact(row[2]);
    }
    List<Map<String, Object>> daily = new ArrayList<>();
    for (int day = 1; day <= m.lengthOfMonth(); day++) {
      daily.add(
          Map.of(
              "day",
              String.format("%02d", day),
              "revenue",
              dailyRevenue[day - 1],
              "orders",
              dailyOrders[day - 1]));
    }
    var products =
        db.queryForList(
            "select i.product_id as id,i.name as name,i.category as category,sum(i.quantity) as"
                + " quantity,sum((i.unit_price+i.options_price)*i.quantity) as"
                + " revenue,sum((i.unit_cost+i.options_cost)*i.quantity) as cost from order_items i"
                + " join orders o on o.id=i.order_id where o.paid_at>=? and o.paid_at<?"
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
                + " quantity,sum((i.unit_price+i.options_price)*i.quantity) as revenue from"
                + " order_items i join orders o on o.id=i.order_id where o.paid_at>=? and"
                + " o.paid_at<?"
                + filter
                + " group by i.product_id order by quantity desc,revenue desc limit 5",
            tp.toArray());
    var branches =
        db.queryForList(
            "select id,name,monthly_target from branches"
                + (branch == null ? "" : " where id=?")
                + " order by name",
            branch == null ? new Object[] {} : new Object[] {branch});
    var branchRows =
        db.query(
            "select o.branch_id,sum(o.total) as revenue,count(*) as orders from orders o where"
                + " o.paid_at>=? and o.paid_at<?"
                + filter
                + " group by o.branch_id",
            (r, n) -> new Object[] {r.getString(1), r.getLong(2), r.getInt(3)},
            params.toArray());
    Map<String, Object[]> branchTotals = new HashMap<>();
    for (Object[] row : branchRows) branchTotals.put((String) row[0], row);
    List<Map<String, Object>> performance = new ArrayList<>();
    for (var b : branches) {
      Object[] totals = branchTotals.get((String) b.get("id"));
      long rev = totals == null ? 0L : (long) totals[1];
      int orders = totals == null ? 0 : (int) totals[2];
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
              orders,
              "target",
              target,
              "achievement",
              target == 0 ? 0 : Math.round(rev * 1000.0 / target) / 10.0));
    }
    var categories = new LinkedHashMap<String, Long>();
    for (var p : products)
      categories.merge(
          (String) p.get("category"), ((Number) p.get("revenue")).longValue(), Long::sum);
    var hourlyRows =
        db.query(
            "select ((o.paid_at+28800000)/3600000)%24 as taipei_hour,count(*) as orders"
                + " from orders o where o.paid_at>=? and o.paid_at<?"
                + filter
                + " group by ((o.paid_at+28800000)/3600000)%24",
            (r, n) -> new long[] {r.getLong(1), r.getLong(2)},
            params.toArray());
    long[] hourlyOrders = new long[24];
    for (long[] row : hourlyRows) hourlyOrders[Math.toIntExact(row[0])] = row[1];
    var hourly = new ArrayList<Map<String, Object>>();
    for (int h = 0; h < 24; h++) {
      hourly.add(Map.of("hour", String.format("%02d:00", h), "orders", hourlyOrders[h]));
    }
    long[] totals =
        db.queryForObject(
            "select coalesce(sum(o.total),0),coalesce(sum(o.discount_amount),0),count(*),"
                + "coalesce(sum(case when o.payment_method='CASH' then 1 else 0 end),0),"
                + "coalesce(sum(case when o.payment_method='ECPAY' then 1 else 0 end),0),"
                + "coalesce(sum(case when o.fulfillment='TAKEAWAY' then 1 else 0 end),0)"
                + " from orders o where o.paid_at>=? and o.paid_at<?"
                + filter,
            (r, n) ->
                new long[] {
                  r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6)
                },
            params.toArray());
    long revenue = totals[0], discount = totals[1], count = totals[2];
    return Map.ofEntries(
        Map.entry("month", month),
        Map.entry("today", today.toString()),
        Map.entry("revenue", revenue),
        Map.entry("discount", discount),
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
        Map.entry("cashOrders", totals[3]),
        Map.entry("onlineOrders", totals[4]),
        Map.entry("takeawayOrders", totals[5]));
  }
}
