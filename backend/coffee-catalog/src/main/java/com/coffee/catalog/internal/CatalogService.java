package com.coffee.catalog.internal;

import com.coffee.catalog.api.Catalog;
import com.coffee.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService implements Catalog {
  private final JdbcTemplate db;

  public CatalogService(JdbcTemplate db) {
    this.db = db;
  }

  private Product productRow(ResultSet r, int n) throws SQLException {
    return new Product(
        r.getString("id"), r.getString("name"), r.getString("subtitle"),
        r.getString("category"), r.getInt("price"), r.getInt("cost"),
        r.getString("image"), r.getString("badge"), r.getBoolean("active"), List.of());
  }

  private OptionItem itemRow(ResultSet r, int n) throws SQLException {
    return new OptionItem(
        r.getString("id"), r.getString("group_id"), r.getString("name"),
        r.getInt("price_delta"), r.getInt("cost_delta"), r.getBoolean("active"),
        r.getInt("sort_order"));
  }

  private OptionGroup groupRow(ResultSet r, int n) throws SQLException {
    return new OptionGroup(
        r.getString("id"), r.getString("name"), r.getString("selection"),
        r.getInt("min_select"), r.getInt("max_select"), r.getBoolean("active"),
        r.getInt("sort_order"), List.of());
  }

  private OptionItem maskCost(OptionItem item) {
    return new OptionItem(
        item.id(), item.groupId(), item.name(), item.priceDelta(), 0, item.active(),
        item.sortOrder());
  }

  private OptionGroup withItems(OptionGroup group, List<OptionItem> items, boolean showCost) {
    var visibleItems = showCost ? items : items.stream().map(this::maskCost).toList();
    return new OptionGroup(
        group.id(), group.name(), group.selection(), group.minSelect(), group.maxSelect(),
        group.active(), group.sortOrder(), visibleItems);
  }

  private List<OptionGroup> productOptions(String productId, boolean showCost) {
    var groups = db.query(
        "select g.* from option_groups g join product_option_groups p on p.group_id=g.id"
            + " where p.product_id=? and g.active=true order by p.sort_order,g.sort_order,g.name",
        this::groupRow, productId);
    if (groups.isEmpty()) return List.of();
    var items = db.query(
            "select i.* from option_items i join product_option_groups p on p.group_id=i.group_id"
                + " where p.product_id=? and i.active=true order by p.sort_order,i.sort_order,i.name",
            this::itemRow, productId).stream()
        .collect(Collectors.groupingBy(
            OptionItem::groupId, LinkedHashMap::new, Collectors.toList()));
    return groups.stream()
        .map(group -> withItems(group, items.getOrDefault(group.id(), List.of()), showCost))
        .toList();
  }

  @Override
  public List<Product> list(Actor actor, boolean manage) {
    if (manage) requireManager(actor);
    return db.query(
            "select * from products " + (manage ? "" : "where active=true")
                + " order by sort_order,name", this::productRow).stream()
        .map(product -> new Product(
            product.id(), product.name(), product.subtitle(), product.category(), product.price(),
            manage ? product.cost() : 0, product.image(), product.badge(), product.active(),
            productOptions(product.id(), manage)))
        .toList();
  }

  @Override
  public Product sellable(String id) {
    var product = db.query(
            "select * from products where id=? and active=true", this::productRow, id).stream()
        .findFirst().orElseThrow(() -> new Problem(400, "商品已下架，請重新整理菜單"));
    return new Product(
        product.id(), product.name(), product.subtitle(), product.category(), product.price(),
        product.cost(), product.image(), product.badge(), product.active(), productOptions(id, false));
  }

  @Override
  public Product save(Actor actor, Product product) {
    requireManager(actor);
    Problem.check(product.name() != null && !product.name().isBlank()
        && product.name().length() <= 80, "商品名稱需為 1–80 字");
    Problem.check(product.price() > 0 && product.price() <= 100000 && product.cost() >= 0
        && product.cost() <= 100000, "價格或成本不正確");
    Problem.check(product.category() != null
        && Set.of("經典咖啡", "風味特調", "茶與其他", "手作烘焙")
            .contains(product.category()), "請選擇有效分類");
    Problem.check(product.image() != null && Set.of("latte", "pastry").contains(product.image()),
        "請選擇有效圖片");
    Problem.check(product.subtitle() != null && product.subtitle().length() <= 200
        && product.badge() != null && product.badge().length() <= 20, "商品說明過長");
    String id = product.id() == null ? Ids.next() : product.id();
    if (product.id() == null) {
      db.update(
          "insert into products(id,name,subtitle,category,price,cost,image,badge,active,sort_order)"
              + " values(?,?,?,?,?,?,?,?,?,99)", id, product.name(), product.subtitle(),
          product.category(), product.price(), product.cost(), product.image(), product.badge(),
          product.active());
    } else if (db.update(
        "update products set name=?,subtitle=?,category=?,price=?,cost=?,image=?,badge=?,active=?"
            + " where id=?", product.name(), product.subtitle(), product.category(), product.price(),
        product.cost(), product.image(), product.badge(), product.active(), id) == 0) {
      throw new Problem(404, "找不到商品");
    }
    return new Product(
        id, product.name(), product.subtitle(), product.category(), product.price(), product.cost(),
        product.image(), product.badge(), product.active(), productOptions(id, true));
  }

  @Override
  public List<BranchAvailability> availability(Actor actor, String branchId) {
    actor.require("MENU_AVAILABILITY");
    actor.branch(branchId);
    int today = today();
    return db.query(
        "select p.id,p.name,b.availability,b.sold_out_date,b.updated_at,b.updated_by"
            + " from products p left join branch_products b"
            + " on b.product_id=p.id and b.branch_id=? order by p.sort_order,p.name",
        (r, n) -> {
          String value = r.getString("availability");
          Integer soldOutDate = (Integer) r.getObject("sold_out_date");
          if (value == null || ("SOLD_OUT".equals(value) && !Objects.equals(soldOutDate, today))) {
            value = "AVAILABLE";
          }
          Long updatedAt = (Long) r.getObject("updated_at");
          return new BranchAvailability(
              branchId,
              r.getString("id"),
              r.getString("name"),
              value,
              updatedAt,
              r.getString("updated_by"));
        },
        branchId);
  }

  @Override
  @Transactional
  public BranchAvailability setAvailability(
      Actor actor, String branchId, String productId, String availability) {
    Problem.check(
        availability != null
            && Set.of("AVAILABLE", "SOLD_OUT", "UNLISTED").contains(availability),
        "供應狀態不正確");
    var products = db.queryForList("select id,name from products where id=? for update", productId);
    if (products.isEmpty()) {
      throw new Problem(404, "找不到商品");
    }
    boolean restricted =
        "UNLISTED".equals(availability)
            || "UNLISTED".equals(currentAvailability(branchId, productId));
    if (restricted) {
      if (!actor.global()) throw new Problem(403, "分店供應品項限總部設定");
      actor.require("MENU_MANAGE");
    } else {
      actor.require("MENU_AVAILABILITY");
      actor.branch(branchId);
    }
    long now = System.currentTimeMillis();
    Integer soldOutDate = "SOLD_OUT".equals(availability) ? today() : null;
    if (db.update(
            "update branch_products set availability=?,sold_out_date=?,updated_at=?,updated_by=?"
                + " where branch_id=? and product_id=?",
            availability,
            soldOutDate,
            now,
            actor.id(),
            branchId,
            productId)
        == 0) {
      db.update(
          "insert into branch_products(branch_id,product_id,availability,sold_out_date,updated_at,updated_by)"
              + " values(?,?,?,?,?,?)",
          branchId,
          productId,
          availability,
          soldOutDate,
          now,
          actor.id());
    }
    db.update(
        "insert into audit_log(id,actor_id,action,target_id,created_at) values(?,?,?,?,?)",
        Ids.next(),
        actor.id(),
        "MENU_AVAILABILITY_" + availability,
        branchId + ":" + productId,
        now);
    return new BranchAvailability(
        branchId,
        productId,
        Objects.toString(products.get(0).get("name")),
        availability,
        now,
        actor.id());
  }

  private String currentAvailability(String branchId, String productId) {
    return db.query(
            "select availability from branch_products where branch_id=? and product_id=?",
            (r, n) -> r.getString(1),
            branchId,
            productId)
        .stream()
        .findFirst()
        .orElse("AVAILABLE");
  }

  private int today() {
    return Integer.parseInt(
        LocalDate.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.BASIC_ISO_DATE));
  }

  @Override
  public List<OptionGroup> productOptions(String productId) {
    return productOptions(productId, false);
  }

  @Override
  public List<ResolvedOption> resolveOptions(String productId, List<String> optionIds) {
    Problem.check(optionIds != null && optionIds.size() <= 20, "選項數量超過上限");
    if (new HashSet<>(optionIds).size() != optionIds.size()) {
      throw new Problem(400, "同一個選項不能重複選擇");
    }
    var groups = productOptions(productId, true);
    var bound = groups.stream().collect(Collectors.toMap(OptionGroup::id, Function.identity()));
    var selected = new ArrayList<ResolvedOption>();
    var counts = new HashMap<String, Integer>();
    for (String optionId : optionIds) {
      var item = db.query(
              "select i.* from option_items i join option_groups g on g.id=i.group_id"
                  + " where i.id=? and i.active=true and g.active=true", this::itemRow, optionId)
          .stream().findFirst()
          .orElseThrow(() -> new Problem(400, "選項已停用，請重新整理菜單"));
      var group = bound.get(item.groupId());
      if (group == null) throw new Problem(400, "此商品不提供所選的選項");
      counts.merge(group.id(), 1, Integer::sum);
      selected.add(new ResolvedOption(
          group.id(), group.name(), item.id(), item.name(), item.priceDelta(), item.costDelta()));
    }
    for (OptionGroup group : groups) {
      int count = counts.getOrDefault(group.id(), 0);
      if ("SINGLE".equals(group.selection()) && count > 1) {
        throw new Problem(400, "「" + group.name() + "」只能選擇一項");
      }
      if (count == 0 && group.minSelect() >= 1) {
        throw new Problem(400, "請選擇「" + group.name() + "」");
      }
      if (count < group.minSelect() || count > group.maxSelect()) {
        throw new Problem(400, "「" + group.name() + "」需選擇 " + group.minSelect() + "–"
            + group.maxSelect() + " 項");
      }
    }
    return List.copyOf(selected);
  }

  @Override
  public List<OptionGroup> optionGroups(Actor actor) {
    requireManager(actor);
    var items = db.query(
            "select * from option_items order by group_id,sort_order,name", this::itemRow).stream()
        .collect(Collectors.groupingBy(
            OptionItem::groupId, LinkedHashMap::new, Collectors.toList()));
    return db.query("select * from option_groups order by sort_order,name", this::groupRow).stream()
        .map(group -> withItems(group, items.getOrDefault(group.id(), List.of()), true)).toList();
  }

  @Override
  @Transactional
  public OptionGroup saveOptionGroup(Actor actor, OptionGroup group) {
    requireManager(actor);
    validateName(group.name());
    Problem.check(Set.of("SINGLE", "MULTI").contains(group.selection()), "請選擇有效的選擇方式");
    Problem.check(group.minSelect() >= 0 && group.maxSelect() >= 1, "選擇數量不正確");
    Problem.check(group.minSelect() <= group.maxSelect(), "選擇數量下限不能大於上限");
    if (!group.active() && group.id() != null
        && Boolean.TRUE.equals(db.queryForObject(
            "select exists(select 1 from product_option_groups where group_id=?)",
            Boolean.class, group.id()))) {
      throw new Problem(409, "此選項群組仍有商品使用，請先解除綁定");
    }
    String id = group.id() == null ? Ids.next() : group.id();
    if (group.id() == null) {
      db.update(
          "insert into option_groups(id,name,selection,min_select,max_select,active,sort_order)"
              + " values(?,?,?,?,?,?,?)", id, group.name(), group.selection(), group.minSelect(),
          group.maxSelect(), group.active(), group.sortOrder());
    } else if (db.update(
        "update option_groups set name=?,selection=?,min_select=?,max_select=?,active=?,"
            + "sort_order=? where id=?", group.name(), group.selection(), group.minSelect(),
        group.maxSelect(), group.active(), group.sortOrder(), id) == 0) {
      throw new Problem(404, "找不到指定的選項群組");
    }
    var saved = db.query("select * from option_groups where id=?", this::groupRow, id).stream()
        .findFirst().orElseThrow();
    var items = db.query(
        "select * from option_items where group_id=? order by sort_order,name", this::itemRow, id);
    return withItems(saved, items, true);
  }

  @Override
  public OptionItem saveOptionItem(Actor actor, OptionItem item) {
    requireManager(actor);
    validateName(item.name());
    Problem.check(item.priceDelta() >= 0 && item.priceDelta() <= 10000
        && item.costDelta() >= 0 && item.costDelta() <= 10000,
        "加價金額需為 0 至 10000 元");
    if (db.queryForObject(
        "select count(*) from option_groups where id=?", Integer.class, item.groupId()) == 0) {
      throw new Problem(400, "找不到指定的選項群組");
    }
    String id = item.id() == null ? Ids.next() : item.id();
    if (item.id() == null) {
      db.update(
          "insert into option_items(id,group_id,name,price_delta,cost_delta,active,sort_order)"
              + " values(?,?,?,?,?,?,?)", id, item.groupId(), item.name(), item.priceDelta(),
          item.costDelta(), item.active(), item.sortOrder());
    } else if (db.update(
        "update option_items set group_id=?,name=?,price_delta=?,cost_delta=?,active=?,"
            + "sort_order=? where id=?", item.groupId(), item.name(), item.priceDelta(),
        item.costDelta(), item.active(), item.sortOrder(), id) == 0) {
      throw new Problem(404, "找不到指定的選項項目");
    }
    return new OptionItem(
        id, item.groupId(), item.name(), item.priceDelta(), item.costDelta(), item.active(),
        item.sortOrder());
  }

  @Override
  @Transactional
  public void bindProductOptions(Actor actor, String productId, List<String> groupIds) {
    requireManager(actor);
    Problem.check(groupIds != null, "找不到指定的選項群組");
    if (db.queryForObject("select count(*) from products where id=?", Integer.class, productId) == 0) {
      throw new Problem(400, "找不到指定的商品");
    }
    var distinct = new LinkedHashSet<>(groupIds);
    if (distinct.size() != groupIds.size()) throw new Problem(400, "選項群組不能重複");
    for (String groupId : groupIds) {
      if (db.queryForObject(
          "select count(*) from option_groups where id=?", Integer.class, groupId) == 0) {
        throw new Problem(400, "找不到指定的選項群組");
      }
    }
    db.update("delete from product_option_groups where product_id=?", productId);
    int sortOrder = 1;
    for (String groupId : groupIds) {
      db.update(
          "insert into product_option_groups(product_id,group_id,sort_order) values(?,?,?)",
          productId, groupId, sortOrder++);
    }
  }

  private void requireManager(Actor actor) {
    actor.require("MENU_MANAGE");
    if (!actor.global()) throw new Problem(403, "菜單管理限總部範圍");
  }

  private void validateName(String name) {
    Problem.check(name != null && !name.isBlank() && name.length() <= 40, "名稱需為 1–40 字");
  }
}
