package com.coffee.payments.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface Reconciliation {
  record Pending(
      String orderId,
      String branchId,
      String branchName,
      int total,
      long createdAt,
      String lastOutcome,
      Long lastQueriedAt,
      int attempts) {}

  record Attempt(
      String outcome,
      String triggerSource,
      String tradeStatus,
      Integer tradeAmount,
      String detail,
      long queriedAt) {}

  record Result(String orderId, String outcome, String detail, long queriedAt) {}

  List<Pending> pending(Actor actor);

  List<Attempt> history(Actor actor, String orderId);

  Result reconcile(Actor actor, String orderId);
}
