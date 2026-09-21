package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.app.bootstrap.InitialData;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DiscountMigrationTest {
  private DriverManagerDataSource database() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:discount-migration-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "");
  }

  @Test
  void freshDatabaseContainsDiscountSchemaAndIndex() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);

    assertThat(
            db.queryForList(
                "select table_name from information_schema.tables"
                    + " where table_name in ('discounts','order_discounts') order by table_name",
                String.class))
        .containsExactly("discounts", "order_discounts");
    assertThat(
            db.queryForList(
                "select column_name from information_schema.columns where table_name='orders'",
                String.class))
        .contains("discount_amount");
    assertThat(
            db.queryForList(
                "select index_name from information_schema.indexes where table_name='discounts'",
                String.class))
        .contains("idx_discounts_code_active");
  }

  @Test
  void upgradePreservesExistingOrdersWithZeroDiscount() {
    var source = database();
    Flyway.configure().dataSource(source).target("8").load().migrate();
    var db = new JdbcTemplate(source);
    new InitialData(db, true, "bootstrap", "TestPassword!2026", "TestPassword!2026")
        .run(new DefaultApplicationArguments());
    int before = db.queryForObject("select count(*) from orders", Integer.class);
    assertThat(before).isPositive();

    Flyway.configure().dataSource(source).load().migrate();

    assertThat(db.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(before);
    assertThat(
            db.queryForObject(
                "select count(*) from orders where discount_amount<>0", Integer.class))
        .isZero();
  }
}
