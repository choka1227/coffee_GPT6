package com.coffee.branches.internal;

import com.coffee.branches.api.Branches;
import com.coffee.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BranchService implements Branches {
  private final JdbcTemplate db;

  public BranchService(JdbcTemplate db) {
    this.db = db;
  }

  private Branch row(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Branch(
        r.getString("id"),
        r.getString("name"),
        r.getString("address"),
        r.getString("phone"),
        r.getBoolean("active"),
        r.getInt("monthly_target"));
  }

  public List<Branch> list(Actor a, boolean manage) {
    if (manage) {
      a.require("BRANCH_MANAGE");
    }
    return db.query(
        "select * from branches " + (manage ? "" : "where active=true ") + " order by name",
        this::row);
  }

  public Branch requireOpen(String id) {
    return db.query("select * from branches where id=? and active=true", this::row, id).stream()
        .findFirst()
        .orElseThrow(() -> new Problem(400, "分店不存在或已暫停營業"));
  }

  public Branch save(Actor a, Branch b) {
    a.require("BRANCH_MANAGE");
    if (!a.global()) throw new Problem(403, "此功能限總部範圍");
    Problem.check(
        b.name() != null && !b.name().isBlank() && b.name().length() <= 80, "請填寫分店名稱（80 字內）");
    Problem.check(b.monthlyTarget() >= 0, "目標不能為負數");
    Problem.check(
        b.address() != null
            && b.address().length() <= 200
            && b.phone() != null
            && b.phone().length() <= 30,
        "地址或電話格式錯誤");
    String id = b.id() == null ? Ids.next() : b.id();
    if (b.id() == null)
      db.update(
          "insert into branches(id,name,address,phone,active,monthly_target) values(?,?,?,?,?,?)",
          id,
          b.name(),
          b.address(),
          b.phone(),
          b.active(),
          b.monthlyTarget());
    else if (db.update(
            "update branches set name=?,address=?,phone=?,active=?,monthly_target=? where id=?",
            b.name(),
            b.address(),
            b.phone(),
            b.active(),
            b.monthlyTarget(),
            id)
        == 0) throw new Problem(404, "找不到分店");
    return new Branch(id, b.name(), b.address(), b.phone(), b.active(), b.monthlyTarget());
  }
}
