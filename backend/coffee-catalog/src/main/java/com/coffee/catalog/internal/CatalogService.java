package com.coffee.catalog.internal;

import com.coffee.catalog.api.Catalog;
import com.coffee.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CatalogService implements Catalog {
  private final JdbcTemplate db;

  public CatalogService(JdbcTemplate db) {
    this.db = db;
  }

  private Product row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Product(
        r.getString("id"),
        r.getString("name"),
        r.getString("subtitle"),
        r.getString("category"),
        r.getInt("price"),
        r.getInt("cost"),
        r.getString("image"),
        r.getString("badge"),
        r.getBoolean("active"));
  }

  public List<Product> list(Actor a, boolean manage) {
    if (manage) a.require("MENU_MANAGE");
    var rows =
        db.query(
            "select * from products "
                + (manage ? "" : "where active=true")
                + " order by sort_order,name",
            this::row);
    if (manage) return rows;
    return rows.stream()
        .map(
            p ->
                new Product(
                    p.id(),
                    p.name(),
                    p.subtitle(),
                    p.category(),
                    p.price(),
                    0,
                    p.image(),
                    p.badge(),
                    p.active()))
        .toList();
  }

  public Product sellable(String id) {
    return db.query("select * from products where id=? and active=true", this::row, id).stream()
        .findFirst()
        .orElseThrow(() -> new Problem(400, "商品已下架，請重新整理菜單"));
  }

  public Product save(Actor a, Product p) {
    a.require("MENU_MANAGE");
    Problem.check(a.global(), "菜單管理限總部範圍");
    Problem.check(
        p.name() != null && !p.name().isBlank() && p.name().length() <= 80, "商品名稱需為 1–80 字");
    Problem.check(
        p.price() > 0 && p.price() <= 100000 && p.cost() >= 0 && p.cost() <= 100000, "價格或成本不正確");
    Problem.check(
        p.category() != null && Set.of("經典咖啡", "風味特調", "茶與其他", "手作烘焙").contains(p.category()),
        "請選擇有效分類");
    Problem.check(p.image() != null && Set.of("latte", "pastry").contains(p.image()), "請選擇有效圖片");
    Problem.check(
        p.subtitle() != null
            && p.subtitle().length() <= 200
            && p.badge() != null
            && p.badge().length() <= 20,
        "商品說明過長");
    String id = p.id() == null ? Ids.next() : p.id();
    if (p.id() == null)
      db.update(
          "insert into products(id,name,subtitle,category,price,cost,image,badge,active,sort_order)"
              + " values(?,?,?,?,?,?,?,?,?,99)",
          id,
          p.name(),
          p.subtitle(),
          p.category(),
          p.price(),
          p.cost(),
          p.image(),
          p.badge(),
          p.active());
    else if (db.update(
            "update products set"
                + " name=?,subtitle=?,category=?,price=?,cost=?,image=?,badge=?,active=? where"
                + " id=?",
            p.name(),
            p.subtitle(),
            p.category(),
            p.price(),
            p.cost(),
            p.image(),
            p.badge(),
            p.active(),
            id)
        == 0) throw new Problem(404, "找不到商品");
    return new Product(
        id,
        p.name(),
        p.subtitle(),
        p.category(),
        p.price(),
        p.cost(),
        p.image(),
        p.badge(),
        p.active());
  }
}
