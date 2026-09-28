package com.coffee.catalog.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface Discounts {
  record Rule(
      String id,
      String code,
      String name,
      String kind,
      int percent,
      int amount,
      int minSubtotal,
      String branchId,
      Long startsAt,
      Long endsAt,
      Integer maxRedemptions,
      int redeemedCount,
      boolean active) {}

  record Applied(
      String discountId,
      String code,
      String name,
      String kind,
      int percent,
      int amount,
      int subtotal,
      int discountAmount) {}

  List<Rule> list(Actor actor);

  Rule save(Actor actor, Rule rule);

  Applied apply(String code, String branchId, int subtotal, long atEpochMs);
}
