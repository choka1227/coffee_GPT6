package com.coffee.orders.api;

import com.coffee.shared.Actor;
import java.util.List;

public interface Orders {
  record LineInput(String productId, int quantity, String temperature, String sugar) {}

  record Create(
      String branchId,
      String fulfillment,
      String paymentMethod,
      String note,
      List<LineInput> items) {}

  record Line(
      String productId,
      String name,
      String category,
      int unitPrice,
      int quantity,
      String temperature,
      String sugar) {}

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

  Order paymentSnapshot(String id);
}
