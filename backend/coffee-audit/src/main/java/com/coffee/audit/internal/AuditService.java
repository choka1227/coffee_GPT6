package com.coffee.audit.internal;

import com.coffee.audit.api.Audit;
import com.coffee.shared.Actor;
import com.coffee.shared.Ids;
import com.coffee.shared.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuditService implements Audit {
  private final AuditWriter writer;
  private final JdbcTemplate db;

  public AuditService(AuditWriter writer, JdbcTemplate db) {
    this.writer = writer;
    this.db = db;
  }

  @Override
  public void record(
      Actor actor, String action, String targetId, String branchId, String summary) {
    Entry entry =
        new Entry(
            Ids.next(),
            actor == null ? "system" : actor.id(),
            truncate(actor == null ? "系統" : actor.name(), 80),
            Objects.requireNonNull(action, "action"),
            truncate(Objects.requireNonNull(targetId, "targetId"), 80),
            branchId,
            truncate(summary, 200),
            System.currentTimeMillis());
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              writeQuietly(entry);
            }
          });
    } else {
      writeQuietly(entry);
    }
  }

  @Override
  public Page search(Actor actor, Query query) {
    actor.require("AUDIT_VIEW");
    if (actor.customer()) throw new Problem(403, "沒有此功能的操作權限");

    String branch = trim(query.branchId());
    if (!actor.global()) {
      if (branch != null && !Objects.equals(branch, actor.branchId())) {
        throw new Problem(403, "只能存取所屬分店資料");
      }
      branch = actor.branchId();
    }
    int limit = query.limit() <= 0 ? 50 : Math.min(query.limit(), 200);
    Cursor cursor = decodeCursor(query.cursor());
    var where = new StringBuilder(" where 1=1");
    List<Object> params = new ArrayList<>();
    addEquals(where, params, "action", trim(query.action()));
    addEquals(where, params, "actor_id", trim(query.actorId()));
    addEquals(where, params, "branch_id", branch);
    if (query.from() != null) {
      where.append(" and created_at>=?");
      params.add(query.from());
    }
    if (query.to() != null) {
      where.append(" and created_at<?");
      params.add(query.to());
    }
    if (cursor != null) {
      where.append(" and (created_at,id)<(?,?)");
      params.add(cursor.createdAt());
      params.add(cursor.id());
    }
    params.add(limit + 1);
    List<Entry> rows =
        db.query(
            "select id,actor_id,actor_name,action,target_id,branch_id,summary,created_at"
                + " from audit_log"
                + where
                + " order by created_at desc,id desc limit ?",
            (r, n) ->
                new Entry(
                    r.getString("id"),
                    r.getString("actor_id"),
                    r.getString("actor_name"),
                    r.getString("action"),
                    r.getString("target_id"),
                    r.getString("branch_id"),
                    r.getString("summary"),
                    r.getLong("created_at")),
            params.toArray());
    boolean more = rows.size() > limit;
    List<Entry> items = more ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
    Entry last = items.isEmpty() ? null : items.get(items.size() - 1);
    return new Page(items, more ? last.createdAt() + ":" + last.id() : null);
  }

  private static void addEquals(
      StringBuilder where, List<Object> params, String column, String value) {
    if (value == null) return;
    where.append(" and ").append(column).append("=?");
    params.add(value);
  }

  private static String trim(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }

  static Cursor decodeCursor(String value) {
    if (value == null || value.isBlank()) return null;
    int split = value.indexOf(':');
    try {
      Problem.check(split > 0 && split < value.length() - 1, "稽核游標格式不正確");
      return new Cursor(Long.parseLong(value.substring(0, split)), value.substring(split + 1));
    } catch (NumberFormatException e) {
      throw new Problem(400, "稽核游標格式不正確");
    }
  }

  record Cursor(long createdAt, String id) {}

  private void writeQuietly(Entry entry) {
    try {
      writer.write(entry);
    } catch (RuntimeException ignored) {
      // Auditing is best effort and must never change an already committed business result.
    }
  }

  private static String truncate(String value, int limit) {
    if (value == null) return "";
    return value.length() <= limit ? value : value.substring(0, limit);
  }
}
