package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.app.bootstrap.InitialData;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CashSessionsMigrationTest {
  private DriverManagerDataSource database() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:cash-migration-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "");
  }

  @Test
  void freshDatabaseContainsCashSessionSchemaAndIndexes() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);

    assertThat(
            db.queryForList(
                "select column_name from information_schema.columns"
                    + " where table_name='cash_sessions' order by ordinal_position",
                String.class))
        .contains(
            "branch_id", "status", "opening_float", "opened_by", "opened_at",
            "closed_by", "closed_at", "counted_amount", "expected_amount", "variance", "note");
    assertThat(
            db.queryForList(
                "select index_name from information_schema.indexes"
                    + " where table_name='cash_sessions'",
                String.class))
        .contains("idx_cash_sessions_open", "idx_cash_sessions_branch_opened");
    assertThat(
            db.queryForList(
                "select column_name from information_schema.columns"
                    + " where table_name='orders'",
                String.class))
        .contains("cash_session_id");
  }

  @Test
  void upgradePreservesOrdersAndGrantsExistingOperationalRoles() {
    var source = database();
    Flyway.configure().dataSource(source).target("5").load().migrate();
    var db = new JdbcTemplate(source);
    new InitialData(db, true, "bootstrap", "TestPassword!2026", "TestPassword!2026")
        .run(new DefaultApplicationArguments());
    db.update("delete from role_permissions where permission='CASH_SESSION'");
    int before = db.queryForObject("select count(*) from orders", Integer.class);

    Flyway.configure().dataSource(source).load().migrate();

    assertThat(db.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(before);
    assertThat(
            db.queryForList(
                "select role_code from role_permissions where permission='CASH_SESSION'"
                    + " order by role_code",
                String.class))
        .containsExactly("CASHIER", "HQ", "MANAGER");
  }
}
