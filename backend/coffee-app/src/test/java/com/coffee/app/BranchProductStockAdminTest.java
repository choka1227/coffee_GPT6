package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.catalog.api.Catalog;
import com.coffee.identity.api.Identity;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:stock-admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
class BranchProductStockAdminTest {
  @Autowired Catalog catalog;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;

  Actor cashier;
  Actor customer;

  @BeforeEach
  void reset() {
    db.update("delete from branch_product_stock_reservation");
    db.update("delete from branch_product_stock");
    db.update("delete from audit_log where action='STOCK_SET'");
    cashier = identity.find("cashier");
    customer = identity.find("customer");
  }

  @Test
  void listsEveryActiveProductAndSupportsSetAndClear() {
    var initial = catalog.stock(cashier, "taipei");
    assertThat(initial).hasSize(
        db.queryForObject("select count(*) from products where active=true", Integer.class));
    assertThat(initial)
        .allSatisfy(
            stock -> {
              assertThat(stock.quantity()).isNull();
              assertThat(stock.remaining()).isNull();
            });

    var saved = catalog.setStock(cashier, "taipei", "latte", 12);
    assertThat(saved.quantity()).isEqualTo(12);
    assertThat(saved.remaining()).isEqualTo(12);
    assertThat(catalog.stock(cashier, "taipei"))
        .filteredOn(stock -> stock.productId().equals("latte"))
        .singleElement()
        .satisfies(
            stock -> {
              assertThat(stock.quantity()).isEqualTo(12);
              assertThat(stock.remaining()).isEqualTo(12);
              assertThat(stock.updatedBy()).isEqualTo(cashier.id());
            });

    var cleared = catalog.setStock(cashier, "taipei", "latte", null);
    assertThat(cleared.quantity()).isNull();
    assertThat(cleared.remaining()).isNull();
    assertThat(countStock("taipei", "latte")).isZero();
  }

  @Test
  void enforcesPermissionBranchProductAndQuantityBoundaries() {
    assertProblem(403, "只能存取所屬分店資料", () -> catalog.stock(cashier, "banqiao"));
    assertProblem(
        403,
        "只能存取所屬分店資料",
        () -> catalog.setStock(cashier, "banqiao", "latte", 1));
    assertProblem(
        403,
        "沒有此功能的操作權限",
        () -> catalog.setStock(customer, "taipei", "latte", 1));
    Actor withoutPermission = new Actor(
        cashier.id(), cashier.username(), cashier.name(), cashier.role(), cashier.scope(),
        cashier.branchId(), Set.of());
    assertProblem(
        403,
        "沒有此功能的操作權限",
        () -> catalog.setStock(withoutPermission, "taipei", "latte", 1));
    assertProblem(
        404, "找不到商品", () -> catalog.setStock(cashier, "taipei", "missing", 1));
    assertProblem(
        400, "可售數量需為 0–9999", () -> catalog.setStock(cashier, "taipei", "latte", -1));
    assertProblem(
        400,
        "可售數量需為 0–9999",
        () -> catalog.setStock(cashier, "taipei", "latte", 10000));

    assertThat(catalog.setStock(cashier, "taipei", "latte", 0).remaining()).isZero();
    assertThat(catalog.setStock(cashier, "taipei", "latte", 9999).quantity()).isEqualTo(9999);
  }

  @Test
  void changingQuantityPreservesSoldCountAndWritesScopedAudit() {
    catalog.setStock(cashier, "taipei", "latte", 5);
    db.update(
        "update branch_product_stock set remaining=3"
            + " where branch_id='taipei' and product_id='latte' and on_date=?",
        today());

    var saved = catalog.setStock(cashier, "taipei", "latte", 1);

    assertThat(saved.quantity()).isEqualTo(1);
    assertThat(saved.remaining()).isZero();
    assertThat(
            db.queryForList(
                "select branch_id,summary from audit_log"
                    + " where action='STOCK_SET' and target_id='latte' order by created_at"))
        .allSatisfy(row -> assertThat(row.get("branch_id")).isEqualTo("taipei"));
    assertThat(
            db.queryForObject(
                "select summary from audit_log where action='STOCK_SET'"
                    + " and target_id='latte' order by created_at desc limit 1",
                String.class))
        .contains("今日可售 1 份，剩餘 0 份");
  }

  @Test
  void startingANewLimitClearsOnlyMatchingOldReservations() {
    insertReservation("same-row", "latte", "taipei", today());
    insertReservation("other-product", "croissant", "taipei", today());
    insertReservation("other-day", "latte", "taipei", today() - 1);

    catalog.setStock(cashier, "taipei", "latte", 5);

    assertThat(
            db.queryForList(
                "select order_id from branch_product_stock_reservation order by order_id",
                String.class))
        .containsExactly("other-day", "other-product");

    insertReservation("current-limit", "latte", "taipei", today());
    catalog.setStock(cashier, "taipei", "latte", 6);
    assertThat(
            db.queryForList(
                "select order_id from branch_product_stock_reservation where product_id='latte'",
                String.class))
        .containsExactlyInAnyOrder("other-day", "current-limit");
  }

  private int countStock(String branchId, String productId) {
    return db.queryForObject(
        "select count(*) from branch_product_stock where branch_id=? and product_id=?",
        Integer.class,
        branchId,
        productId);
  }

  private void insertReservation(String orderId, String productId, String branchId, int onDate) {
    db.update(
        "insert into branch_product_stock_reservation"
            + "(order_id,product_id,branch_id,on_date,quantity,created_at) values(?,?,?,?,?,?)",
        orderId,
        productId,
        branchId,
        onDate,
        1,
        1L);
  }

  private int today() {
    return Integer.parseInt(
        LocalDate.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.BASIC_ISO_DATE));
  }

  private static void assertProblem(
      int status, String message, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(status);
              assertThat(problem).hasMessage(message);
            });
  }
}
