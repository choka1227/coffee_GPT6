package com.coffee.app;

import static org.assertj.core.api.Assertions.*;

import com.coffee.app.bootstrap.InitialData;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BranchProductStockMigrationTest {
  private DriverManagerDataSource database() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:stock-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "");
  }

  private JdbcTemplate migratedDatabase() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);
    new InitialData(db, true, "bootstrap", "TestPassword!2026", "TestPassword!2026")
        .run(new DefaultApplicationArguments());
    return db;
  }

  @Test
  void createsBothTablesAndTheirIndexesWithoutAddingPermissions() {
    var db = migratedDatabase();

    assertThat(
            db.queryForList(
                "select table_name from information_schema.tables"
                    + " where table_name in ('branch_product_stock',"
                    + "'branch_product_stock_reservation') order by table_name",
                String.class))
        .containsExactly("branch_product_stock", "branch_product_stock_reservation");
    assertThat(
            db.queryForList(
                "select index_name from information_schema.indexes"
                    + " where index_name in ('idx_branch_product_stock_date',"
                    + "'idx_bps_reservation_row') order by index_name",
                String.class))
        .containsExactly("idx_bps_reservation_row", "idx_branch_product_stock_date");
    assertThat(
            db.queryForObject(
                "select count(*) from role_permissions where permission='STOCK_MANAGE'",
                Integer.class))
        .isZero();
  }

  @Test
  void stockChecksAndPrimaryKeyProtectDailyQuantities() {
    var db = migratedDatabase();
    insertStock(db, 5, 3);

    assertThatThrownBy(() -> insertStock(db, 5, 3))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertStock(db, -1, 0))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertStock(db, 10000, 0))
        .isInstanceOf(DataIntegrityViolationException.class);
    db.update("delete from branch_product_stock");
    assertThatThrownBy(() -> insertStock(db, 2, 3))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void reservationChecksPrimaryKeyButDeliberatelyHasNoOrderForeignKey() {
    var db = migratedDatabase();

    db.update(
        "insert into branch_product_stock_reservation"
            + "(order_id,product_id,branch_id,on_date,quantity,created_at) values(?,?,?,?,?,?)",
        "order-that-does-not-exist",
        "latte",
        "taipei",
        20261003,
        2,
        1L);
    assertThatThrownBy(
            () ->
                db.update(
                    "insert into branch_product_stock_reservation"
                        + "(order_id,product_id,branch_id,on_date,quantity,created_at)"
                        + " values(?,?,?,?,?,?)",
                    "order-that-does-not-exist",
                    "latte",
                    "taipei",
                    20261003,
                    1,
                    2L))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "insert into branch_product_stock_reservation"
                        + "(order_id,product_id,branch_id,on_date,quantity,created_at)"
                        + " values(?,?,?,?,?,?)",
                    "another-order",
                    "latte",
                    "taipei",
                    20261003,
                    0,
                    2L))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insertStock(JdbcTemplate db, int quantity, int remaining) {
    db.update(
        "insert into branch_product_stock"
            + "(branch_id,product_id,on_date,quantity,remaining,updated_at,updated_by)"
            + " values(?,?,?,?,?,?,?)",
        "taipei",
        "latte",
        20261003,
        quantity,
        remaining,
        1L,
        "cashier");
  }
}
