package com.coffee.orders.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface Orders {
  record LineInput(String productId, int quantity, List<String> optionIds) {}

  record Create(
      String branchId,
      String fulfillment,
      String paymentMethod,
      String note,
      List<LineInput> items) {}

  record LineOption(String groupName, String optionName, int priceDelta) {}

  record Line(
      String productId,
      String name,
      String category,
      int unitPrice,
      int quantity,
      String temperature,
      String sugar,
      int optionsPrice,
      int lineTotal,
      List<LineOption> options) {}

  record Order(
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
      Integer changeAmount,
      List<Line> items) {}

  Order create(Actor a, Create request, String key);

  List<Order> list(Actor a);

  Order get(Actor a, String id);

  Order cash(Actor a, String id, int tendered);

  Order transition(Actor a, String id, String status);

  Order payable(Actor a, String id);

  void confirmOnline(String id, int amount, String providerTradeNo);

  void confirmOnline(String id, int amount, String providerTradeNo, long paidAt);

  List<Order> reconciliationCandidates(Actor actor, long since, long until, int limit, int offset);

  Order paymentSnapshot(String id);
}
