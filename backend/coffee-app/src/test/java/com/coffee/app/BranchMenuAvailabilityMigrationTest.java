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

class BranchMenuAvailabilityMigrationTest {
  private DriverManagerDataSource database() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:availability-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "");
  }

  private void seed(JdbcTemplate db) {
    new InitialData(db, true, "bootstrap", "TestPassword!2026", "TestPassword!2026")
        .run(new DefaultApplicationArguments());
  }

  @Test
  void freshDatabaseSeedsAvailabilityPermissionForOperationalRoles() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);
    seed(db);

    assertThat(
            db.queryForList(
                "select role_code from role_permissions where permission='MENU_AVAILABILITY'"
                    + " order by role_code",
                String.class))
        .containsExactly("CASHIER", "HQ", "MANAGER");
  }

  @Test
  void upgradeAddsPermissionToExistingRolesAndIsIdempotent() {
    var source = database();
    Flyway.configure().dataSource(source).target("3").load().migrate();
    var db = new JdbcTemplate(source);
    seed(db);

    var flyway = Flyway.configure().dataSource(source).load();
    flyway.migrate();
    flyway.migrate();

    assertThat(
            db.queryForList(
                "select role_code from role_permissions where permission='MENU_AVAILABILITY'"
                    + " order by role_code",
                String.class))
        .containsExactly("CASHIER", "HQ", "MANAGER");
  }

  @Test
  void branchProductConstraintsProtectAvailabilityStateAndReferences() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);
    seed(db);

    db.update(
        "insert into branch_products values(?,?,?,?,?,?)",
        "taipei",
        "latte",
        "SOLD_OUT",
        20260919,
        1L,
        "manager");

    assertThatThrownBy(
            () ->
                db.update(
                    "insert into branch_products values(?,?,?,?,?,?)",
                    "banqiao",
                    "latte",
                    "SOLD_OUT",
                    null,
                    1L,
                    "manager2"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "insert into branch_products values(?,?,?,?,?,?)",
                    "banqiao",
                    "latte",
                    "AVAILABLE",
                    20260919,
                    1L,
                    "manager2"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "insert into branch_products values(?,?,?,?,?,?)",
                    "taipei",
                    "latte",
                    "AVAILABLE",
                    null,
                    1L,
                    "manager"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "insert into branch_products values(?,?,?,?,?,?)",
                    "missing",
                    "latte",
                    "AVAILABLE",
                    null,
                    1L,
                    "manager"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
