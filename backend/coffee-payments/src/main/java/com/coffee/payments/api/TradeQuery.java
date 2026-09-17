package com.coffee.payments.api;

import java.util.Map;

/** Transport boundary; responses must be authenticated by the reconciliation service. */
public interface TradeQuery {
  Map<String, String> query(String orderId);
}
