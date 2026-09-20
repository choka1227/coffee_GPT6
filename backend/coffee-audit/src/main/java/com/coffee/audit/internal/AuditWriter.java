package com.coffee.audit.internal;

import com.coffee.audit.api.Audit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuditWriter {
  private final JdbcTemplate db;

  public AuditWriter(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void write(Audit.Entry entry) {
    db.update(
        "insert into audit_log(id,actor_id,actor_name,action,target_id,branch_id,summary,created_at)"
            + " values(?,?,?,?,?,?,?,?)",
        entry.id(),
        entry.actorId(),
        entry.actorName(),
        entry.action(),
        entry.targetId(),
        entry.branchId(),
        entry.summary(),
        entry.createdAt());
  }
}
