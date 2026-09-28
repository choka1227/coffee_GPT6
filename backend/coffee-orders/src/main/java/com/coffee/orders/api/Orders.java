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
      String discountCode,
      List<LineInput> items) {}

  record OrderDiscount(
      String code, String name, String kind, int percent, int amount, int discountAmount) {}

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
      int subtotal,
      int discountAmount,
      OrderDiscount discount,
      String note,
      long createdAt,
      Long paidAt,
      Integer tendered,
      Integer changeAmount,
      List<Line> items) {}

  record Query(
      String status, String branchId, Long from, Long to, String q, String cursor, int limit) {}

  record Page(List<Order> items, String nextCursor) {}

  Order create(Actor a, Create request, String key);

  Page page(Actor a, Query query);

  Order get(Actor a, String id);

  Order cash(Actor a, String id, int tendered);

  Order transition(Actor a, String id, String status);

  Order payable(Actor a, String id);

  void confirmOnline(String id, int amount, String providerTradeNo);

  void confirmOnline(String id, int amount, String providerTradeNo, long paidAt);

  /** 只回表頭欄位，items() 固定為空 List（對帳不需要品項，避免 N+1）。 */
  List<Order> reconciliationCandidates(Actor actor, long since, long until, int limit, int offset);

  Order paymentSnapshot(String id);
}
