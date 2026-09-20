package com.coffee.orders.internal;

import com.coffee.audit.api.Audit;
import com.coffee.orders.api.CashSessions;
import com.coffee.shared.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashSessionService implements CashSessions {
  private final JdbcTemplate db;
  private final Audit audit;

  public CashSessionService(JdbcTemplate db, Audit audit) {
    this.db = db;
    this.audit = audit;
  }

  @Override
  @Transactional
  public Session open(Actor actor, Open request) {
    actor.require("CASH_SESSION");
    Problem.check(request != null, "金額不正確");
    String branchId = requireBranch(actor, request == null ? null : request.branchId());
    int openingFloat = request.openingFloat();
    Problem.check(openingFloat >= 0 && openingFloat <= 1_000_000, "金額不正確");
    String note = note(request.note());

    lockBranch(branchId);
    if (!db.queryForList(
            "select id from cash_sessions where branch_id=? and status='OPEN'", String.class, branchId)
        .isEmpty()) {
      throw new Problem(409, "此分店已有開啟中的班別，請先交班");
    }
    String id = Ids.next();
    db.update(
        "insert into cash_sessions(id,branch_id,status,opening_float,opened_by,opened_at,note)"
            + " values(?,?,'OPEN',?,?,?,?)",
        id,
        branchId,
        openingFloat,
        actor.id(),
        System.currentTimeMillis(),
        note);
    audit.record(
        actor, "CASH_OPEN", id, branchId, "開班準備金 " + openingFloat + " 元");
    return session(id, true);
  }

  @Override
  public Session current(Actor actor, String requestedBranchId) {
    actor.require("CASH_SESSION");
    String branchId = requireBranch(actor, requestedBranchId);
    List<String> ids =
        db.queryForList(
            "select id from cash_sessions where branch_id=? and status='OPEN'",
            String.class,
            branchId);
    if (ids.isEmpty()) return null;
    Session result = session(ids.get(0), true);
    return "OPEN".equals(result.status()) ? result : null;
  }

  @Override
  public Session get(Actor actor, String id) {
    actor.require("CASH_SESSION");
    String branchId = branchOf(id);
    actor.branch(branchId);
    return session(id, "OPEN".equals(statusOf(id)));
  }

  @Override
  public Page search(Actor actor, Query query) {
    actor.require("CASH_SESSION");
    if (actor.customer()) throw new Problem(403, "沒有此功能的操作權限");
    Problem.check(query != null, "班別查詢條件不正確");
    String branchId = requireBranch(actor, query.branchId());
    int limit = query.limit() <= 0 ? 50 : Math.min(query.limit(), 200);
    Cursor cursor = decodeCursor(query.cursor());
    var where = new StringBuilder(" where branch_id=?");
    List<Object> params = new ArrayList<>();
    params.add(branchId);
    if (query.from() != null) {
      where.append(" and opened_at>=?");
      params.add(query.from());
    }
    if (query.to() != null) {
      where.append(" and opened_at<?");
      params.add(query.to());
    }
    if (cursor != null) {
      where.append(" and (opened_at<? or (opened_at=? and id<?))");
      params.add(cursor.openedAt());
      params.add(cursor.openedAt());
      params.add(cursor.id());
    }
    params.add(limit + 1);
    List<String> ids =
        db.queryForList(
            "select id from cash_sessions" + where
                + " order by opened_at desc,id desc limit ?",
            String.class,
            params.toArray());
    boolean more = ids.size() > limit;
    List<String> pageIds = more ? ids.subList(0, limit) : ids;
    List<Session> items =
        pageIds.stream().map(id -> session(id, "OPEN".equals(statusOf(id)))).toList();
    Session last = items.isEmpty() ? null : items.get(items.size() - 1);
    int[] unassigned = unassignedCash(branchId, query.from(), query.to());
    return new Page(
        items,
        more ? last.openedAt() + ":" + last.id() : null,
        unassigned[0],
        unassigned[1]);
  }

  @Override
  @Transactional
  public Session close(Actor actor, String id, Close request) {
    actor.require("CASH_SESSION");
    Problem.check(request != null, "金額不正確");
    String branchId = branchOf(id);
    actor.branch(branchId);
    Problem.check(
        request.countedAmount() >= 0 && request.countedAmount() <= 10_000_000,
        "金額不正確");
    String note = note(request.note());

    lockBranch(branchId);
    var rows =
        db.queryForList(
            "select status,opening_float from cash_sessions where id=? for update", id);
    if (rows.isEmpty()) throw new Problem(404, "找不到這個班別");
    if (!"OPEN".equals(rows.get(0).get("status"))) throw new Problem(409, "此班別已交班");
    int openingFloat = ((Number) rows.get(0).get("opening_float")).intValue();
    int cashRevenue = cashRevenue(id);
    int expected = Math.addExact(openingFloat, cashRevenue);
    int variance = Math.subtractExact(request.countedAmount(), expected);
    db.update(
        "update cash_sessions set status='CLOSED',closed_by=?,closed_at=?,counted_amount=?,"
            + " expected_amount=?,variance=? where id=?",
        actor.id(),
        System.currentTimeMillis(),
        request.countedAmount(),
        expected,
        variance,
        id);
    if (!note.isEmpty()) db.update("update cash_sessions set note=? where id=?", note, id);
    audit.record(
        actor,
        "CASH_CLOSE",
        id,
        branchId,
        "應有 " + expected + " 元，實點 " + request.countedAmount() + " 元，"
            + (variance < 0 ? "短少 " + Math.abs(variance) : "溢收 " + variance) + " 元");
    return session(id, false);
  }

  private String requireBranch(Actor actor, String requested) {
    if (actor.global()) Problem.check(requested != null && !requested.isBlank(), "請選擇分店");
    String branchId = actor.global() ? requested : actor.branchId();
    actor.branch(requested == null ? branchId : requested);
    if (branchId == null || branchId.isBlank()) throw new Problem(403, "只能存取所屬分店資料");
    return branchId;
  }

  private String branchOf(String id) {
    var rows = db.queryForList("select branch_id from cash_sessions where id=?", String.class, id);
    if (rows.isEmpty()) throw new Problem(404, "找不到這個班別");
    return rows.get(0);
  }

  private String statusOf(String id) {
    List<String> rows =
        db.queryForList("select status from cash_sessions where id=?", String.class, id);
    if (rows.isEmpty()) throw new Problem(404, "找不到這個班別");
    return rows.get(0);
  }

  private int[] unassignedCash(String branchId, Long from, Long to) {
    var sql = new StringBuilder(
        "select coalesce(sum(total),0) revenue,count(*) orders from orders"
            + " where branch_id=? and cash_session_id is null"
            + " and payment_method='CASH' and paid_at is not null");
    List<Object> params = new ArrayList<>();
    params.add(branchId);
    if (from != null) {
      sql.append(" and paid_at>=?");
      params.add(from);
    }
    if (to != null) {
      sql.append(" and paid_at<?");
      params.add(to);
    }
    var row = db.queryForMap(sql.toString(), params.toArray());
    return new int[] {
      Math.toIntExact(((Number) row.get("revenue")).longValue()),
      ((Number) row.get("orders")).intValue()
    };
  }

  static Cursor decodeCursor(String value) {
    if (value == null || value.isBlank()) return null;
    int split = value.indexOf(':');
    try {
      Problem.check(split > 0 && split < value.length() - 1, "班別游標格式不正確");
      return new Cursor(Long.parseLong(value.substring(0, split)), value.substring(split + 1));
    } catch (NumberFormatException e) {
      throw new Problem(400, "班別游標格式不正確");
    }
  }

  record Cursor(long openedAt, String id) {}

  private void lockBranch(String branchId) {
    if (db.queryForList("select id from branches where id=? for update", String.class, branchId)
        .isEmpty()) throw new Problem(404, "找不到分店");
  }

  private int cashRevenue(String id) {
    Number value =
        db.queryForObject(
            "select coalesce(sum(total),0) from orders where cash_session_id=?"
                + " and payment_method='CASH' and paid_at is not null",
            Number.class,
            id);
    return Math.toIntExact(value.longValue());
  }

  private int orderCount(String id) {
    return db.queryForObject(
        "select count(*) from orders where cash_session_id=?"
            + " and payment_method='CASH' and paid_at is not null",
        Integer.class,
        id);
  }

  private Session session(String id, boolean live) {
    var rows =
        db.queryForList(
            "select s.*,op.name opened_by_name,cl.name closed_by_name from cash_sessions s"
                + " join accounts op on op.id=s.opened_by"
                + " left join accounts cl on cl.id=s.closed_by where s.id=?",
            id);
    if (rows.isEmpty()) throw new Problem(404, "找不到這個班別");
    var row = rows.get(0);
    int openingFloat = ((Number) row.get("opening_float")).intValue();
    int expected = live ? Math.addExact(openingFloat, cashRevenue(id))
        : ((Number) row.get("expected_amount")).intValue();
    int revenue = Math.subtractExact(expected, openingFloat);
    return new Session(
        (String) row.get("id"), (String) row.get("branch_id"), (String) row.get("status"),
        openingFloat, revenue, orderCount(id), expected,
        row.get("counted_amount") == null ? null : ((Number) row.get("counted_amount")).intValue(),
        row.get("variance") == null ? null : ((Number) row.get("variance")).intValue(),
        (String) row.get("opened_by"), (String) row.get("opened_by_name"),
        ((Number) row.get("opened_at")).longValue(), (String) row.get("closed_by"),
        (String) row.get("closed_by_name"),
        row.get("closed_at") == null ? null : ((Number) row.get("closed_at")).longValue(),
        (String) row.get("note"));
  }

  private String note(String value) {
    String note = value == null ? "" : value.trim();
    Problem.check(note.length() <= 200, "備註最多 200 字");
    return note;
  }
}
