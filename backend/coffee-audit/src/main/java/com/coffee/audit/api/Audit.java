package com.coffee.audit.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface Audit {
  record Entry(
      String id,
      String actorId,
      String actorName,
      String action,
      String targetId,
      String branchId,
      String summary,
      long createdAt) {}

  record Page(List<Entry> items, String nextCursor) {}

  record Query(
      String action,
      String actorId,
      String branchId,
      Long from,
      Long to,
      String cursor,
      int limit) {}

  void record(Actor actor, String action, String targetId, String branchId, String summary);

  Page search(Actor actor, Query query);
}
