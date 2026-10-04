package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.orders.api.Orders;
import com.coffee.shared.Actor;
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
      "spring.datasource.url=jdbc:h2:mem:item-promotion-order;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
class ItemPromotionOrderTest {
  @Autowired Orders orders;
  @Autowired JdbcTemplate db;

  private final Actor cashier =
      new Actor(
          "cashier",
          "cashier",
          "收銀員",
          "CASHIER",
          "BRANCH",
          "taipei",
          Set.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE"));

  @BeforeEach
  void clean() {
    db.update("delete from order_item_options");
    db.update("delete from order_item_promotions");
    db.update("delete from order_discounts");
    db.update("delete from order_items");
    db.update("delete from branch_product_stock_reservation");
    db.update("delete from orders");
    db.update("delete from item_promotions");
    db.update("delete from discounts");
    db.update("delete from audit_log where action in ('ORDER_ITEM_DISCOUNT','ORDER_DISCOUNT')");
  }

  @Test
  void itemAndCodeDiscountsPreserveAllFourAmountIdentities() {
    insertPromotion("promo-bogo", true, null, null, null);
    insertDiscount("SAVE-10", 10);

    var order = create("SAVE-10", UUID.randomUUID().toString());

    assertThat(order.subtotal()).isEqualTo(280);
    assertThat(order.itemDiscountAmount()).isEqualTo(140);
    assertThat(order.discount().discountAmount()).isEqualTo(14);
    assertThat(order.discountAmount()).isEqualTo(154);
    assertThat(order.total()).isEqualTo(126);
    assertThat(order.itemPromotion().promotionId()).isEqualTo("promo-bogo");
    assertThat(order.itemPromotion().discountedUnits()).isEqualTo(1);
    assertThat(order.items()).singleElement().satisfies(line -> {
      assertThat(line.quantity()).isEqualTo(2);
      assertThat(line.lineTotal()).isEqualTo(280);
      assertThat(line.discountAmount()).isEqualTo(140);
    });
    assertThat(db.queryForObject(
        "select subtotal from order_discounts where order_id=?", Integer.class, order.id()))
        .isEqualTo(140);
    assertThat(db.queryForObject(
        "select sum(discount_amount) from order_items where order_id=?", Integer.class, order.id()))
        .isEqualTo(order.itemDiscountAmount());
    assertThat(order.total() + order.discountAmount()).isEqualTo(order.subtotal());
  }

  @Test
  void inactiveFutureExpiredAndOtherBranchRulesAreIgnored() {
    insertPromotion("inactive", false, null, null, null);
    insertPromotion("future", true, Long.MAX_VALUE, null, null);
    insertPromotion("expired", true, null, 1L, null);
    insertPromotion("other-branch", true, null, null, "taichung");

    var order = create(null, UUID.randomUUID().toString());

    assertThat(order.itemDiscountAmount()).isZero();
    assertThat(order.discountAmount()).isZero();
    assertThat(order.total()).isEqualTo(280);
    assertThat(order.itemPromotion()).isNull();
    assertThat(order.items()).singleElement().extracting(Orders.Line::discountAmount).isEqualTo(0);
  }

  @Test
  void writesAuditAndIdempotentReplayDoesNotDuplicateSnapshot() {
    insertPromotion("promo-bogo", true, null, null, null);
    String key = UUID.randomUUID().toString();

    var first = create(null, key);
    var replay = create(null, key);

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(db.queryForObject(
        "select count(*) from order_item_promotions where order_id=?", Integer.class, first.id()))
        .isEqualTo(1);
    assertThat(db.queryForObject(
        "select count(*) from audit_log where action='ORDER_ITEM_DISCOUNT' and target_id=?",
        Integer.class,
        first.id()))
        .isEqualTo(1);
    assertThat(db.queryForObject(
        "select summary from audit_log where action='ORDER_ITEM_DISCOUNT' and target_id=?",
        String.class,
        first.id()))
        .contains("買一送一", "140 元", "1 件");
  }

  private Orders.Order create(String code, String key) {
    return orders.create(
        cashier,
        new Orders.Create(
            "taipei",
            "TAKEAWAY",
            "CASH",
            "",
            code,
            List.of(new Orders.LineInput(
                "latte", 2, List.of("temp-hot", "sugar-none")))),
        key);
  }

  private void insertPromotion(
      String id, boolean active, Long startsAt, Long endsAt, String branchId) {
    long createdAt = 1_000L;
    db.update(
        "insert into item_promotions(id,name,kind,percent,nth,target_kind,product_id,category,"
            + "branch_id,starts_at,ends_at,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        "買一送一",
        "NTH_PERCENT",
        100,
        2,
        "PRODUCT",
        "latte",
        null,
        branchId,
        startsAt,
        endsAt,
        active,
        createdAt,
        createdAt);
  }

  private void insertDiscount(String code, int percent) {
    long createdAt = 1_000L;
    db.update(
        "insert into discounts(id,code,name,kind,percent,amount,min_subtotal,branch_id,starts_at,"
            + "ends_at,max_redemptions,redeemed_count,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        code,
        code,
        "PERCENT",
        percent,
        0,
        0,
        null,
        null,
        null,
        null,
        0,
        true,
        createdAt,
        createdAt);
  }
}
