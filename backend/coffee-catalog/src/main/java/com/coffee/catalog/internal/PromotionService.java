package com.coffee.catalog.internal;

import com.coffee.audit.api.Audit;
import com.coffee.catalog.api.Promotions;
import com.coffee.shared.Actor;
import com.coffee.shared.Ids;
import com.coffee.shared.Problem;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionService implements Promotions {
  private final JdbcTemplate db;
  private final Audit audit;

  public PromotionService(JdbcTemplate db, Audit audit) {
    this.db = db;
    this.audit = audit;
  }

  private Rule row(ResultSet r, int n) throws SQLException {
    return new Rule(
        r.getString("id"),
        r.getString("name"),
        r.getString("kind"),
        r.getInt("percent"),
        r.getInt("nth"),
        r.getString("target_kind"),
        r.getString("product_id"),
        r.getString("category"),
        r.getString("branch_id"),
        (Long) r.getObject("starts_at"),
        (Long) r.getObject("ends_at"),
        r.getBoolean("active"));
  }

  @Override
  public List<Rule> list(Actor actor) {
    requireHeadquarters(actor);
    return db.query("select * from item_promotions order by id", this::row);
  }

  @Override
  @Transactional
  public Rule save(Actor actor, Rule requested) {
    requireHeadquarters(actor);
    if (requested == null) throw new Problem(400, "請提供品項促銷");
    Problem.check(
        requested.name() != null
            && !requested.name().isBlank()
            && requested.name().length() <= 40,
        "促銷名稱需為 1–40 字");
    Problem.check(
        requested.kind() != null
            && Set.of("ITEM_PERCENT", "NTH_PERCENT").contains(requested.kind()),
        "促銷型態不正確");
    int nth = requested.nth();
    if ("ITEM_PERCENT".equals(requested.kind())) {
      Problem.check(
          requested.percent() >= 1 && requested.percent() <= 90,
          "每件折扣的百分比需為 1–90");
      nth = 0;
    } else {
      Problem.check(
          requested.percent() >= 1 && requested.percent() <= 100,
          "第 N 件折扣的百分比需為 1–100");
      Problem.check(nth >= 2, "第 N 件折扣的 N 需大於或等於 2");
    }
    Problem.check(
        requested.targetKind() != null
            && Set.of("PRODUCT", "CATEGORY").contains(requested.targetKind()),
        "促銷目標不正確");

    String productId = null;
    String category = null;
    if ("PRODUCT".equals(requested.targetKind())) {
      productId = requested.productId();
      if (productId == null
          || db.queryForObject(
                  "select count(*) from products where id=?", Integer.class, productId)
              == 0) {
        throw new Problem(404, "找不到指定的商品");
      }
    } else {
      Problem.check(
          requested.category() != null
              && !requested.category().isBlank()
              && requested.category().length() <= 40,
          "促銷分類需為 1–40 字");
      category = requested.category().trim();
    }
    if (requested.branchId() != null
        && db.queryForObject(
                "select count(*) from branches where id=?", Integer.class, requested.branchId())
            == 0) {
      throw new Problem(404, "找不到指定的分店");
    }
    Problem.check(
        requested.startsAt() == null
            || requested.endsAt() == null
            || requested.startsAt() <= requested.endsAt(),
        "促銷結束時間不能早於開始時間");

    boolean creating = requested.id() == null || requested.id().isBlank();
    String id = creating ? Ids.next() : requested.id();
    if (!creating
        && db.queryForObject(
                "select count(*) from item_promotions where id=?", Integer.class, id)
            == 0) {
      throw new Problem(404, "找不到品項促銷");
    }
    long now = System.currentTimeMillis();
    String name = requested.name().trim();
    if (creating) {
      db.update(
          "insert into item_promotions(id,name,kind,percent,nth,target_kind,product_id,category,"
              + "branch_id,starts_at,ends_at,active,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id,
          name,
          requested.kind(),
          requested.percent(),
          nth,
          requested.targetKind(),
          productId,
          category,
          requested.branchId(),
          requested.startsAt(),
          requested.endsAt(),
          requested.active(),
          now,
          now);
    } else {
      db.update(
          "update item_promotions set name=?,kind=?,percent=?,nth=?,target_kind=?,product_id=?,"
              + "category=?,branch_id=?,starts_at=?,ends_at=?,active=?,updated_at=? where id=?",
          name,
          requested.kind(),
          requested.percent(),
          nth,
          requested.targetKind(),
          productId,
          category,
          requested.branchId(),
          requested.startsAt(),
          requested.endsAt(),
          requested.active(),
          now,
          id);
    }
    String target = productId == null ? category : productId;
    audit.record(
        actor,
        "PROMOTION_SAVE",
        id,
        null,
        (creating ? "新增" : "更新")
            + "品項促銷 "
            + name
            + "（"
            + requested.kind()
            + " / "
            + target
            + "）");
    return new Rule(
        id,
        name,
        requested.kind(),
        requested.percent(),
        nth,
        requested.targetKind(),
        productId,
        category,
        requested.branchId(),
        requested.startsAt(),
        requested.endsAt(),
        requested.active());
  }

  @Override
  public List<ActiveRule> active(Actor actor, String branchId) {
    Problem.check(branchId != null && !branchId.isBlank(), "請選擇分店");
    return activeRules(branchId, System.currentTimeMillis()).stream()
        .map(
            rule ->
                new ActiveRule(
                    rule.id(),
                    rule.name(),
                    rule.kind(),
                    rule.percent(),
                    rule.nth(),
                    rule.targetKind(),
                    "PRODUCT".equals(rule.targetKind()) ? rule.productId() : rule.category()))
        .toList();
  }

  @Override
  public Applied apply(String branchId, List<Line> lines, long atEpochMs) {
    return best(activeRules(branchId, atEpochMs), lines);
  }

  private List<Rule> activeRules(String branchId, long atEpochMs) {
    return db.query(
        "select * from item_promotions where active=true"
            + " and (branch_id is null or branch_id=?)"
            + " and (starts_at is null or starts_at<=?)"
            + " and (ends_at is null or ends_at>=?) order by id",
        this::row,
        branchId,
        atEpochMs,
        atEpochMs);
  }

  static Applied best(List<Rule> rules, List<Line> lines) {
    Applied selected = null;
    for (var rule : rules) {
      var candidate = evaluate(rule, lines);
      if (candidate != null
          && candidate.discountAmount() > 0
          && (selected == null
              || candidate.discountAmount() > selected.discountAmount()
              || (candidate.discountAmount() == selected.discountAmount()
                  && candidate.promotionId().compareTo(selected.promotionId()) < 0))) {
        selected = candidate;
      }
    }
    return selected;
  }

  static Applied evaluate(Rule rule, List<Line> lines) {
    var units = new ArrayList<Unit>();
    for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
      var line = lines.get(lineIndex);
      boolean matches = "PRODUCT".equals(rule.targetKind())
          ? rule.productId().equals(line.productId())
          : rule.category().equals(line.category());
      if (!matches) continue;
      for (int quantity = 0; quantity < line.quantity(); quantity++) {
        units.add(new Unit(lineIndex, line.unitPrice()));
      }
    }
    if (units.isEmpty()) return null;

    int discountedUnits;
    if ("ITEM_PERCENT".equals(rule.kind())) {
      discountedUnits = units.size();
    } else {
      discountedUnits = units.size() / rule.nth();
      if (discountedUnits == 0) return null;
      units.sort(Comparator.comparingInt(Unit::unitPrice).thenComparingInt(Unit::lineIndex));
    }

    var lineDiscounts = new ArrayList<Integer>(lines.size());
    for (int index = 0; index < lines.size(); index++) lineDiscounts.add(0);
    int discountAmount = 0;
    int limit = "ITEM_PERCENT".equals(rule.kind()) ? units.size() : discountedUnits;
    for (int index = 0; index < limit; index++) {
      var unit = units.get(index);
      int discount = Math.multiplyExact(unit.unitPrice(), rule.percent()) / 100;
      lineDiscounts.set(
          unit.lineIndex(), Math.addExact(lineDiscounts.get(unit.lineIndex()), discount));
      discountAmount = Math.addExact(discountAmount, discount);
    }
    return new Applied(
        rule.id(),
        rule.name(),
        rule.kind(),
        rule.percent(),
        rule.nth(),
        rule.targetKind(),
        "PRODUCT".equals(rule.targetKind()) ? rule.productId() : rule.category(),
        discountedUnits,
        discountAmount,
        List.copyOf(lineDiscounts));
  }

  private record Unit(int lineIndex, int unitPrice) {}

  private static void requireHeadquarters(Actor actor) {
    actor.require("MENU_MANAGE");
    if (!actor.global()) throw new Problem(403, "只有總部可以維護品項促銷");
  }
}
