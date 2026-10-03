package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffee.app.bootstrap.InitialData;
import com.coffee.identity.api.Identity;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BranchDaySettingsMigrationTest {
  private DriverManagerDataSource database() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:branch-day-settings-"
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
  void freshDatabaseSeedsPermissionAndNullableDailyLastOrder() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);
    seed(db);

    assertThat(Identity.PERMISSIONS).hasSize(14).contains("BRANCH_HOURS_OVERRIDE");
    assertThat(
            db.queryForList(
                "select role_code from role_permissions"
                    + " where permission='BRANCH_HOURS_OVERRIDE' order by role_code",
                String.class))
        .containsExactly("HQ", "MANAGER");
    assertThat(
            db.queryForObject(
                "select is_nullable from information_schema.columns"
                    + " where table_name='branch_day_overrides'"
                    + " and column_name='last_order_minutes'",
                String.class))
        .isEqualTo("YES");

    db.update(
        "insert into branch_day_overrides(branch_id,on_date,closed,note,updated_at,updated_by)"
            + " values('taipei',20261003,false,'',1,'manager')");
    assertThat(
            db.queryForObject(
                "select last_order_minutes from branch_day_overrides"
                    + " where branch_id='taipei' and on_date=20261003",
                Integer.class))
        .isNull();
    assertThatThrownBy(
            () ->
                db.update(
                    "update branch_day_overrides set last_order_minutes=-1"
                        + " where branch_id='taipei' and on_date=20261003"))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "update branch_day_overrides set last_order_minutes=121"
                        + " where branch_id='taipei' and on_date=20261003"))
        .isInstanceOf(Exception.class);
  }

  @Test
  void upgradeGrantsOnlyManagerAndHeadquarters() {
    var source = database();
    Flyway.configure().dataSource(source).target("11").load().migrate();
    var db = new JdbcTemplate(source);
    seed(db);
    db.update("delete from role_permissions where permission='BRANCH_HOURS_OVERRIDE'");

    Flyway.configure().dataSource(source).load().migrate();

    assertThat(
            db.queryForList(
                "select role_code from role_permissions"
                    + " where permission='BRANCH_HOURS_OVERRIDE' order by role_code",
                String.class))
        .containsExactly("HQ", "MANAGER");
  }
}
