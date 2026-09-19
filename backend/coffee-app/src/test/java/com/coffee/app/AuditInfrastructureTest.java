package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffee.audit.api.Audit;
import com.coffee.audit.internal.AuditWriter;
import com.coffee.identity.api.Identity;
import com.coffee.shared.Actor;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:audit-infrastructure;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
@Import(AuditInfrastructureTest.ProbeConfiguration.class)
class AuditInfrastructureTest {
  @Autowired Identity identity;
  @Autowired Audit audit;
  @Autowired JdbcTemplate db;
  @Autowired RollbackProbe rollbackProbe;

  @AfterEach
  void clean() {
    db.execute("alter table audit_log drop constraint if exists reject_account_audit");
    db.update("delete from audit_log where action='TEST' or summary like '%audit.%@example.com'");
    db.update("delete from accounts where username like 'audit.%@example.com'");
    db.update("delete from role_permissions where role_code like 'AUDIT_TEST_%'");
    db.update("delete from roles where code like 'AUDIT_TEST_%'");
  }

  @Test
  void identityActionsKeepTheirActionAndCaptureActorAndSummary() {
    Actor hq = identity.find("hq");
    String username = "audit." + UUID.randomUUID() + "@example.com";

    Identity.Account created =
        identity.saveAccount(
            hq,
            new Identity.AccountInput(
                null, username, "稽核測試", "CUSTOMER", null, true, "TestPassword!2026"));

    assertThat(
            db.queryForMap(
                "select actor_name,action,target_id,branch_id,summary from audit_log"
                    + " where target_id=?",
                created.id()))
        .containsEntry("actor_name", hq.name())
        .containsEntry("action", "ACCOUNT_SAVE")
        .containsEntry("target_id", created.id())
        .containsEntry("branch_id", null)
        .containsEntry("summary", "儲存帳號 " + username);

    String roleCode =
        "AUDIT_TEST_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    identity.saveRole(hq, new Identity.Role(roleCode, "稽核角色測試", "GLOBAL", Set.of()));

    assertThat(
            db.queryForMap(
                "select actor_name,action,target_id,branch_id,summary from audit_log"
                    + " where target_id=?",
                roleCode))
        .containsEntry("actor_name", hq.name())
        .containsEntry("action", "ROLE_SAVE")
        .containsEntry("target_id", roleCode)
        .containsEntry("branch_id", null)
        .containsEntry("summary", "儲存角色 " + roleCode);
  }

  @Test
  void entryValuesAreTruncatedBeforeWriting() {
    String marker = "audit-test-" + UUID.randomUUID();
    Actor actor = new Actor("system-test", "system", "甲".repeat(100), "HQ", "GLOBAL", null, Set.of());

    audit.record(actor, "TEST", marker.repeat(4), null, "摘".repeat(250));

    assertThat(
            db.queryForObject(
                "select length(actor_name) from audit_log where action='TEST'"
                    + " order by created_at desc limit 1",
                Integer.class))
        .isEqualTo(80);
    assertThat(
            db.queryForObject(
                "select length(target_id) from audit_log where action='TEST'"
                    + " order by created_at desc limit 1",
                Integer.class))
        .isEqualTo(80);
    assertThat(
            db.queryForObject(
                "select length(summary) from audit_log where action='TEST'"
                    + " order by created_at desc limit 1",
                Integer.class))
        .isEqualTo(200);
  }

  @Test
  void auditInsertFailureDoesNotRollbackBusinessCommit() {
    db.execute(
        "alter table audit_log add constraint reject_account_audit"
            + " check (action <> 'ACCOUNT_SAVE')");
    Actor hq = identity.find("hq");
    String username = "audit.failure." + UUID.randomUUID() + "@example.com";

    Identity.Account created =
        identity.saveAccount(
            hq,
            new Identity.AccountInput(
                null, username, "稽核失敗測試", "CUSTOMER", null, true, "TestPassword!2026"));

    assertThat(
            db.queryForObject(
                "select count(*) from accounts where id=?", Integer.class, created.id()))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from audit_log where target_id=?", Integer.class, created.id()))
        .isZero();
  }

  @Test
  void rolledBackBusinessTransactionDoesNotLeaveAuditRow() {
    String id = "audit-test-" + UUID.randomUUID();

    assertThatThrownBy(() -> rollbackProbe.changeThenFail(id)).isInstanceOf(IllegalStateException.class);

    assertThat(db.queryForObject("select count(*) from accounts where id=?", Integer.class, id))
        .isZero();
    assertThat(db.queryForObject("select count(*) from audit_log where target_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void writerUsesAnIndependentTransactionBoundary() throws Exception {
    Transactional transactional =
        AuditWriter.class.getMethod("write", Audit.Entry.class).getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
  }

  @TestConfiguration
  static class ProbeConfiguration {
    @Bean
    RollbackProbe rollbackProbe(JdbcTemplate db, Audit audit) {
      return new RollbackProbe(db, audit);
    }
  }

  static class RollbackProbe {
    private final JdbcTemplate db;
    private final Audit audit;

    RollbackProbe(JdbcTemplate db, Audit audit) {
      this.db = db;
      this.audit = audit;
    }

    @Transactional
    public void changeThenFail(String id) {
      db.update(
          "insert into accounts(id,username,name,role_code,branch_id,active,password_hash)"
              + " values(?,?,?,?,?,?,?)",
          id,
          id + "@example.com",
          "回滾測試",
          "CUSTOMER",
          null,
          true,
          "unused");
      audit.record(identityActor(), "ACCOUNT_SAVE", id, null, "應回滾");
      throw new IllegalStateException("rollback");
    }

    private Actor identityActor() {
      return new Actor("hq", "hq@coffee.local", "總部管理員", "HQ", "GLOBAL", null, Set.of());
    }
  }
}
