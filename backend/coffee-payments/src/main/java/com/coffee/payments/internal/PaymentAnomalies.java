package com.coffee.payments.internal;

import com.coffee.orders.api.Orders;
import com.coffee.shared.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

final class PaymentAnomalies {
  private static final Logger log = LoggerFactory.getLogger(PaymentAnomalies.class);
  private final JdbcTemplate db;

  PaymentAnomalies(JdbcTemplate db) {
    this.db = db;
  }

  void record(Orders.Order order, String source, String tradeNo, Integer amount) {
    int inserted =
        db.update(
            "insert into payment_anomalies"
                + "(id,order_id,branch_id,order_total,kind,source,provider_trade_no,trade_amount,detected_at)"
                + " select ?,?,?,?,'PAID_AFTER_EXPIRY',?,?,?,? where not exists"
                + " (select 1 from payment_anomalies where order_id=? and kind='PAID_AFTER_EXPIRY')",
            Ids.next(),
            order.id(),
            order.branchId(),
            order.total(),
            source,
            tradeNo,
            amount,
            System.currentTimeMillis(),
            order.id());
    if (inserted == 1)
      log.warn("綠界作廢後收到付款：訂單 {}，來源 {}，需人工退款", order.id(), source);
  }
}
