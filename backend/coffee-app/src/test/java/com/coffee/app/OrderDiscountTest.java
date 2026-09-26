package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.orders.api.Orders;
import com.coffee.reporting.internal.ReportService;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:order-discount;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
class OrderDiscountTest {
  @Autowired Orders orders;
  @Autowired JdbcTemplate db;
  @Autowired ReportService reports;

  private final Actor cashier =
      new Actor("cashier", "cashier", "收銀員", "CASHIER", "BRANCH", "taipei",
          Set.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE"));
  private final Actor headquarters =
      new Actor("hq", "hq", "總部", "HQ", "GLOBAL", null, Set.of("REPORT_ALL"));

  @BeforeEach
  void clean() {
    db.update("delete from order_discounts");
    db.update("delete from discounts");
  }

  @Test
  void appliesNormalizedCodeAndReturnsImmutableSnapshot() {
    insert("SAVE-10", "PERCENT", 10, 0, 0, null);
    String key = UUID.randomUUID().toString();
    Orders.Order first = create("  save-10 ", key);

    assertThat(first.subtotal()).isEqualTo(140);
    assertThat(first.discountAmount()).isEqualTo(14);
    assertThat(first.total()).isEqualTo(126);
    assertThat(first.discount().code()).isEqualTo("SAVE-10");
    assertThat(first.discount().discountAmount()).isEqualTo(14);
    assertThat(db.queryForObject(
        "select count(*) from audit_log where action='ORDER_DISCOUNT' and target_id=?",
        Integer.class, first.id())).isEqualTo(1);

    Orders.Order replay = create("SAVE-10", key);
    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(count("SAVE-10")).isEqualTo(1);
    db.update("update discounts set name='改名',percent=50 where code='SAVE-10'");
    assertThat(orders.get(cashier, first.id()).discount().name()).isEqualTo("SAVE-10");
    assertThat(orders.get(cashier, first.id()).total()).isEqualTo(126);
  }

  @Test
  void enforcesMinimumAndCapWhileCancellationDoesNotRefundRedemption() {
    insert("MIN-200", "AMOUNT", 0, 20, 200, null);
    assertThatThrownBy(() -> create("MIN-200", UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(Problem.class, p -> assertThat(p.status).isEqualTo(400));
    assertThat(count("MIN-200")).isZero();

    insert("ONLY-ONE", "AMOUNT", 0, 20, 0, 1);
    Orders.Order order = create("ONLY-ONE", UUID.randomUUID().toString());
    orders.transition(cashier, order.id(), "CANCELLED");
    assertThat(count("ONLY-ONE")).isEqualTo(1);
    assertThatThrownBy(() -> create("ONLY-ONE", UUID.randomUUID().toString()))
        .isInstanceOfSatisfying(Problem.class, p -> assertThat(p.status).isEqualTo(409));
  }

  @Test
  void reportShowsDiscountAndRevenueAfterPayment() {
    String month = YearMonth.now().toString();
    var before = reports.report(headquarters, month, null);
    insert("LESS-20", "AMOUNT", 0, 20, 0, null);
    Orders.Order order = create("LESS-20", UUID.randomUUID().toString());
    orders.cash(cashier, order.id(), order.total());
    var report = reports.report(headquarters, month, null);
    assertThat(((Number) report.get("discount")).longValue()
        - ((Number) before.get("discount")).longValue()).isEqualTo(20L);
    assertThat(((Number) report.get("revenue")).longValue()
        - ((Number) before.get("revenue")).longValue()).isEqualTo(order.total());
  }

  private Orders.Order create(String code, String key) {
    return orders.create(
        cashier,
        new Orders.Create(
            "taipei", "TAKEAWAY", "CASH", "", code,
            List.of(new Orders.LineInput("latte", 1, List.of("temp-hot", "sugar-none")))),
        key);
  }

  private void insert(
      String code, String kind, int percent, int amount, int minimum, Integer maximum) {
    long now = System.currentTimeMillis();
    db.update(
        "insert into discounts(id,code,name,kind,percent,amount,min_subtotal,branch_id,starts_at,ends_at,max_redemptions,redeemed_count,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), code, code, kind, percent, amount, minimum, null, null, null,
        maximum, 0, true, now, now);
  }

  private int count(String code) {
    return db.queryForObject(
        "select redeemed_count from discounts where code=?", Integer.class, code);
  }
}
