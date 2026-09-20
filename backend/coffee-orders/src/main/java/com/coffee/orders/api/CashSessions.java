package com.coffee.orders.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface CashSessions {
  record Open(String branchId, int openingFloat, String note) {}

  record Close(int countedAmount, String note) {}

  record Session(
      String id,
      String branchId,
      String status,
      int openingFloat,
      int cashRevenue,
      int orderCount,
      int expectedAmount,
      Integer countedAmount,
      Integer variance,
      String openedBy,
      String openedByName,
      long openedAt,
      String closedBy,
      String closedByName,
      Long closedAt,
      String note) {}

  record Query(String branchId, Long from, Long to, String cursor, int limit) {}

  record Page(
      List<Session> items,
      String nextCursor,
      int unassignedCashRevenue,
      int unassignedOrderCount) {}

  Session open(Actor actor, Open request);

  Session current(Actor actor, String branchId);

  Session get(Actor actor, String id);

  Page search(Actor actor, Query query);

  Session close(Actor actor, String id, Close request);
}
