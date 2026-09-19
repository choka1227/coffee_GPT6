package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffee.app.bootstrap.InitialData;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AuditTrailMigrationTest {
  private DriverManagerDataSource database() {
    return new DriverManagerDataSource(
        "jdbc:h2:mem:audit-migration-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa",
        "");
  }

  @Test
  void freshDatabaseContainsAuditTrailColumnsAndIndexes() {
    var source = database();
    Flyway.configure().dataSource(source).load().migrate();
    var db = new JdbcTemplate(source);

    assertThat(
            db.queryForList(
                "select column_name from information_schema.columns"
                    + " where table_name='audit_log' order by ordinal_position",
                String.class))
        .contains("branch_id", "actor_name", "summary");
    assertThat(
            db.queryForList(
                "select index_name from information_schema.indexes"
                    + " where table_name='audit_log'",
                String.class))
        .contains("idx_audit_created", "idx_audit_branch_created", "idx_audit_action_created");
  }

  @Test
  void upgradePreservesExistingAuditRowsAndGrantsExistingOperationalRoles() {
    var source = database();
    Flyway.configure().dataSource(source).target("4").load().migrate();
    var db = new JdbcTemplate(source);
    new InitialData(db, true, "bootstrap", "TestPassword!2026", "TestPassword!2026")
        .run(new DefaultApplicationArguments());
    db.update(
        "insert into audit_log(id,actor_id,action,target_id,created_at) values(?,?,?,?,?)",
        "before-v5",
        "hq",
        "ROLE_SAVE",
        "TEST_ROLE",
        1L);

    Flyway.configure().dataSource(source).load().migrate();

    assertThat(
            db.queryForMap(
                "select actor_name,summary,branch_id from audit_log where id='before-v5'"))
        .containsEntry("actor_name", "")
        .containsEntry("summary", "")
        .containsEntry("branch_id", null);
    assertThat(
            db.queryForList(
                "select role_code from role_permissions where permission='AUDIT_VIEW'"
                    + " order by role_code",
                String.class))
        .containsExactly("HQ", "MANAGER");
  }
}
