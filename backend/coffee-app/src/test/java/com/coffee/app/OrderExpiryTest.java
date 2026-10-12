package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.shared.Problem;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:order-expiry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "ecpay.expire.enabled=false"
    })
@ActiveProfiles("dev")
@Transactional
class OrderExpiryTest {
  @Autowired Orders orders;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;

  @Test
  void expireOnlineCancelsOnceAndRestoresReservedStock() {
    int today = (int) LocalDate.now(ZoneId.of("Asia/Taipei")).toEpochDay();
    db.update(
        "insert into branch_product_stock"
            + "(branch_id,product_id,on_date,quantity,remaining,updated_at,updated_by)"
            + " values('taipei','latte',?,10,10,1,'hq')",
        today);
    var order = create("ECPAY");
    assertThat(remaining(today)).isEqualTo(8);
    assertThat(reservations(order.id())).isEqualTo(1);

    assertThat(orders.expireOnline(order.id())).isTrue();
    assertThat(orders.paymentSnapshot(order.id()).status()).isEqualTo("CANCELLED");
    assertThat(remaining(today)).isEqualTo(10);
    assertThat(reservations(order.id())).isZero();
    assertThat(orders.expireOnline(order.id())).isFalse();

    TestTransaction.flagForCommit();
    TestTransaction.end();
    assertThat(
            db.queryForObject(
                "select count(*) from audit_log"
                    + " where action='ORDER_EXPIRED' and target_id=? and actor_id='system'",
                Integer.class,
                order.id()))
        .isEqualTo(1);
  }

  @Test
  void expireOnlineRejectsIneligibleOrdersAndMissingId() {
    var paid = create("ECPAY");
    orders.confirmOnline(paid.id(), paid.total(), "trade-paid");
    assertThat(orders.expireOnline(paid.id())).isFalse();
    assertThat(orders.expireOnline(create("CASH").id())).isFalse();
    assertThatThrownBy(() -> orders.expireOnline("MISSING0000000000000"))
        .isInstanceOf(Problem.class)
        .extracting(error -> ((Problem) error).status)
        .isEqualTo(404);
  }

  @Test
  void settleOnlineIsIdempotentAndNeverRevivesCancelledOrder() {
    var pending = create("ECPAY");
    long paidAt = System.currentTimeMillis() - 1_000;
    assertThat(orders.settleOnline(pending.id(), pending.total(), "trade-1", paidAt))
        .isEqualTo("CONFIRMED");
    assertThat(orders.settleOnline(pending.id(), pending.total(), "trade-1", paidAt + 500))
        .isEqualTo("ALREADY_PAID");
    assertThat(orders.paymentSnapshot(pending.id()).paidAt()).isEqualTo(paidAt);
    assertThatThrownBy(
            () -> orders.settleOnline(pending.id(), pending.total(), "trade-2", paidAt + 500))
        .isInstanceOf(Problem.class);

    var cancelled = create("ECPAY");
    assertThat(orders.expireOnline(cancelled.id())).isTrue();
    assertThat(
            orders.settleOnline(
                cancelled.id(), cancelled.total(), "trade-after-expiry", System.currentTimeMillis()))
        .isEqualTo("CANCELLED");
    var unchanged = orders.paymentSnapshot(cancelled.id());
    assertThat(unchanged.status()).isEqualTo("CANCELLED");
    assertThat(unchanged.paidAt()).isNull();
    assertThatThrownBy(
            () ->
                orders.settleOnline(
                    cancelled.id(),
                    cancelled.total() + 1,
                    "trade-after-expiry",
                    System.currentTimeMillis()))
        .isInstanceOf(Problem.class);
  }

  private Orders.Order create(String paymentMethod) {
    return orders.create(
        identity.find("customer"),
        new Orders.Create(
            "taipei",
            "TAKEAWAY",
            paymentMethod,
            "",
            null,
            List.of(new Orders.LineInput("latte", 2, List.of("temp-hot", "sugar-none")))),
        UUID.randomUUID().toString());
  }

  private int remaining(int today) {
    return db.queryForObject(
        "select remaining from branch_product_stock"
            + " where branch_id='taipei' and product_id='latte' and on_date=?",
        Integer.class,
        today);
  }

  private int reservations(String orderId) {
    return db.queryForObject(
        "select count(*) from branch_product_stock_reservation where order_id=?",
        Integer.class,
        orderId);
  }
}
