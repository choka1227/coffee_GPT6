package com.coffee.catalog.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface Promotions {
  record Rule(
      String id,
      String name,
      String kind,
      int percent,
      int nth,
      String targetKind,
      String productId,
      String category,
      String branchId,
      Long startsAt,
      Long endsAt,
      boolean active) {}

  record ActiveRule(
      String id,
      String name,
      String kind,
      int percent,
      int nth,
      String targetKind,
      String targetId) {}

  record Line(String productId, String category, int unitPrice, int quantity) {}

  record Applied(
      String promotionId,
      String name,
      String kind,
      int percent,
      int nth,
      String targetKind,
      String targetId,
      int discountedUnits,
      int discountAmount,
      List<Integer> lineDiscounts) {}

  List<Rule> list(Actor actor);

  List<ActiveRule> active(Actor actor, String branchId);

  Rule save(Actor actor, Rule rule);

  Applied apply(String branchId, List<Line> lines, long atEpochMs);
}
