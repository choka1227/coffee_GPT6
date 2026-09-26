package com.coffee.catalog.internal;

import com.coffee.audit.api.Audit;
import com.coffee.catalog.api.Discounts;
import com.coffee.shared.Actor;
import com.coffee.shared.Ids;
import com.coffee.shared.Problem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscountService implements Discounts {
  private static final String INVALID = "優惠碼不存在或已失效";

  private final JdbcTemplate db;
  private final Audit audit;

  public DiscountService(JdbcTemplate db, Audit audit) {
    this.db = db;
    this.audit = audit;
  }

  private Rule row(ResultSet r, int n) throws SQLException {
    return new Rule(
        r.getString("id"),
        r.getString("code"),
        r.getString("name"),
        r.getString("kind"),
        r.getInt("percent"),
        r.getInt("amount"),
        r.getInt("min_subtotal"),
        r.getString("branch_id"),
        (Long) r.getObject("starts_at"),
        (Long) r.getObject("ends_at"),
        (Integer) r.getObject("max_redemptions"),
        r.getInt("redeemed_count"),
        r.getBoolean("active"));
  }

  @Override
  public List<Rule> list(Actor actor) {
    requireHeadquarters(actor);
    return db.query("select * from discounts order by code", this::row);
  }

  @Override
  @Transactional
  public Rule save(Actor actor, Rule requested) {
    requireHeadquarters(actor);
    if (requested == null) throw new Problem(400, "請提供優惠碼");
    String code = normalize(requested.code());
    Problem.check(code != null && code.matches("[A-Z0-9-]{4,20}"), "優惠碼格式不正確");
    Problem.check(
        requested.name() != null
            && !requested.name().isBlank()
            && requested.name().length() <= 40,
        "優惠碼名稱需為 1–40 字");
    Problem.check(
        requested.kind() != null && Set.of("PERCENT", "AMOUNT").contains(requested.kind()),
        "折扣類型不正確");
    if ("PERCENT".equals(requested.kind())) {
      Problem.check(requested.percent() >= 1 && requested.percent() <= 90, "折扣百分比需為 1–90");
      Problem.check(requested.amount() == 0, "百分比折扣不可設定定額金額");
    } else {
      Problem.check(requested.amount() >= 1 && requested.amount() <= 1_000_000, "折抵金額需為 1 元以上");
      Problem.check(requested.percent() == 0, "定額折扣不可設定百分比");
    }
    Problem.check(
        requested.minSubtotal() >= 0 && requested.minSubtotal() <= 1_000_000,
        "最低消費金額不正確");
    Problem.check(
        requested.startsAt() == null
            || requested.endsAt() == null
            || requested.startsAt() <= requested.endsAt(),
        "優惠期間的起訖時間不正確");
    Problem.check(requested.maxRedemptions() == null || requested.maxRedemptions() > 0,
        "使用次數上限需大於 0");
    if (requested.branchId() != null
        && db.queryForObject(
                "select count(*) from branches where id=?", Integer.class, requested.branchId())
            == 0) {
      throw new Problem(404, "找不到分店");
    }

    String id = requested.id() == null ? Ids.next() : requested.id();
    int redeemed = 0;
    if (requested.id() != null) {
      var current = db.query(
          "select redeemed_count from discounts where id=? for update",
          (r, n) -> r.getInt(1), id);
      if (current.isEmpty()) throw new Problem(404, "找不到優惠碼");
      redeemed = current.get(0);
    }
    long now = System.currentTimeMillis();
    try {
      if (requested.id() == null) {
        db.update(
            "insert into discounts(id,code,name,kind,percent,amount,min_subtotal,branch_id,"
                + "starts_at,ends_at,max_redemptions,redeemed_count,active,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, code, requested.name().trim(), requested.kind(), requested.percent(),
            requested.amount(), requested.minSubtotal(), requested.branchId(), requested.startsAt(),
            requested.endsAt(), requested.maxRedemptions(), 0, requested.active(), now, now);
      } else {
        db.update(
            "update discounts set code=?,name=?,kind=?,percent=?,amount=?,min_subtotal=?,"
                + "branch_id=?,starts_at=?,ends_at=?,max_redemptions=?,active=?,updated_at=? where id=?",
            code, requested.name().trim(), requested.kind(), requested.percent(), requested.amount(),
            requested.minSubtotal(), requested.branchId(), requested.startsAt(), requested.endsAt(),
            requested.maxRedemptions(), requested.active(), now, id);
      }
    } catch (DataIntegrityViolationException duplicate) {
      throw new Problem(409, "優惠碼已存在");
    }
    audit.record(actor, "DISCOUNT_SAVE", id, requested.branchId(), "儲存優惠碼 " + code);
    return new Rule(
        id, code, requested.name().trim(), requested.kind(), requested.percent(), requested.amount(),
        requested.minSubtotal(), requested.branchId(), requested.startsAt(), requested.endsAt(),
        requested.maxRedemptions(), redeemed, requested.active());
  }

  @Override
  @Transactional
  public Applied apply(String requestedCode, String branchId, int subtotal, long atEpochMs) {
    String code = normalize(requestedCode);
    if (code == null) return null;
    Problem.check(code.matches("[A-Z0-9-]{4,20}"), "優惠碼格式不正確");
    Rule rule = db.query(
            "select * from discounts where code=? for update", this::row, code).stream()
        .findFirst()
        .orElseThrow(() -> new Problem(404, INVALID));
    if (!rule.active()
        || (rule.startsAt() != null && rule.startsAt() > atEpochMs)
        || (rule.endsAt() != null && atEpochMs > rule.endsAt())
        || (rule.branchId() != null && !rule.branchId().equals(branchId))) {
      throw new Problem(404, INVALID);
    }
    Problem.check(subtotal >= rule.minSubtotal(), "訂單金額未達此優惠碼的最低消費");
    if (rule.maxRedemptions() != null && rule.redeemedCount() >= rule.maxRedemptions()) {
      throw new Problem(409, "此優惠碼的使用次數已達上限");
    }
    int discountAmount = calculate(rule, subtotal);
    db.update("update discounts set redeemed_count=redeemed_count+1 where id=?", rule.id());
    return new Applied(
        rule.id(), rule.code(), rule.name(), rule.kind(), rule.percent(), rule.amount(), subtotal,
        discountAmount);
  }

  public static int calculate(Rule rule, int subtotal) {
    int raw = "PERCENT".equals(rule.kind())
        ? Math.multiplyExact(subtotal, rule.percent()) / 100
        : rule.amount();
    return Math.max(0, Math.min(raw, Math.subtractExact(subtotal, 1)));
  }

  private static String normalize(String code) {
    if (code == null || code.isBlank()) return null;
    return code.trim().toUpperCase(Locale.ROOT);
  }

  private static void requireHeadquarters(Actor actor) {
    actor.require("MENU_MANAGE");
    if (!actor.global()) throw new Problem(403, "只有總部可以維護優惠碼");
  }
}
