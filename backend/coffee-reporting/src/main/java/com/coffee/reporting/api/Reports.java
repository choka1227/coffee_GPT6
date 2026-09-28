package com.coffee.reporting.api;

import com.coffee.shared.Actor;
import java.util.List;
import java.util.Map;

public interface Reports {
  record Daily(String day, long revenue, int orders) {}

  record BranchPerformance(
      String id, String name, long revenue, int orders, int target, double achievement) {}

  record Hourly(String hour, long orders) {}

  record MonthlyReport(
      String month,
      String today,
      long revenue,
      long discount,
      long orders,
      long averageOrder,
      long quantity,
      long grossProfit,
      double grossMargin,
      List<Daily> daily,
      List<Map<String, Object>> products,
      List<Map<String, Object>> topToday,
      List<BranchPerformance> branches,
      Map<String, Long> categories,
      List<Hourly> hourly,
      long cashOrders,
      long onlineOrders,
      long takeawayOrders) {}

  MonthlyReport report(Actor a, String month, String branchId);
}
