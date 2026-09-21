package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BranchBusinessHoursMigrationTest {
  private DriverManagerDataSource database() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:branch-hours-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "");
  }

  @Test
  void freshDatabaseContainsSchemaIndexesAndChecks() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);

    assertThat(
            db.queryForList(
                "select column_name from information_schema.columns"
                    + " where table_name='branch_hours' order by ordinal_position",
                String.class))
        .containsExactly("id", "branch_id", "day_of_week", "open_minute", "close_minute");
    assertThat(
            db.queryForList(
                "select index_name from information_schema.indexes"
                    + " where table_name='branch_hours'",
                String.class))
        .contains("idx_branch_hours_branch");

    db.update(
        "insert into branches values('branch','分店','地址','02-0000-0000',true,0)");
    assertThatThrownBy(
            () ->
                db.update(
                    "insert into branch_hours values('bad-day','branch',0,0,1)"))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "insert into branch_hours values('bad-open','branch',1,1440,1)"))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                db.update(
                    "insert into branch_hours values('bad-close','branch',1,0,1441)"))
        .isInstanceOf(Exception.class);
  }

  @Test
  void upgradingExistingDatabasePreservesBranchesAndAddsNoHours() {
    var source = database();
    Flyway.configure().dataSource(source).target("7").load().migrate();
    var db = new JdbcTemplate(source);
    db.update(
        "insert into branches"
            + " values('existing','既有分店','地址','02-0000-0000',true,0)");

    Flyway.configure().dataSource(source).load().migrate();

    assertThat(db.queryForObject("select count(*) from branches", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("select count(*) from branch_hours", Integer.class)).isZero();
  }
}
