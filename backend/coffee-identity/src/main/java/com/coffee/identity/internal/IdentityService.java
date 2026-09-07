package com.coffee.identity.internal;

import com.coffee.branches.api.Branches;
import com.coffee.identity.api.Identity;
import com.coffee.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService implements Identity {
  private final JdbcTemplate db;
  private final Branches branches;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final String dummy = encoder.encode("timing-placeholder");

  public IdentityService(JdbcTemplate db, Branches branches) {
    this.db = db;
    this.branches = branches;
  }

  public int sessionVersion(String id) {
    return db.queryForObject("select session_version from accounts where id=?", Integer.class, id);
  }

  public Actor authenticate(String username, String password) {
    var rows =
        db.queryForList(
            "select id,password_hash,active from accounts where username=?",
            username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    String hash = rows.isEmpty() ? dummy : (String) rows.get(0).get("password_hash");
    boolean valid = encoder.matches(password == null ? "" : password, hash);
    if (!valid || rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("active")))
      throw new Problem(401, "帳號或密碼錯誤");
    return find((String) rows.get(0).get("id"));
  }

  public Actor find(String id) {
    var users =
        db.query(
            "select a.*,r.scope from accounts a join roles r on r.code=a.role_code where a.id=? and"
                + " a.active=true",
            (r, n) ->
                new Actor(
                    r.getString("id"),
                    r.getString("username"),
                    r.getString("name"),
                    r.getString("role_code"),
                    r.getString("scope"),
                    r.getString("branch_id"),
                    new HashSet<>(
                        db.queryForList(
                            "select permission from role_permissions where role_code=?",
                            String.class,
                            r.getString("role_code")))),
            id);
    if (users.isEmpty()) throw new Problem(401, "請重新登入");
    return users.get(0);
  }

  public List<Account> accounts(Actor a) {
    a.require("ACCOUNT_MANAGE");
    if (!a.global()) throw new Problem(403, "此功能限總部範圍");
    return db.query(
        "select * from accounts order by username",
        (r, n) ->
            new Account(
                r.getString("id"),
                r.getString("username"),
                r.getString("name"),
                r.getString("role_code"),
                r.getString("branch_id"),
                r.getBoolean("active")));
  }

  @Transactional
  public Account saveAccount(Actor a, AccountInput i) {
    a.require("ACCOUNT_MANAGE");
    Problem.check(a.global(), "帳號管理限總部範圍");
    Problem.check(
        i.username() != null && i.username().matches("[A-Za-z0-9@._+-]{3,100}"),
        "帳號需為 3–100 個英數或電子郵件字元");
    Problem.check(
        i.name() != null && !i.name().isBlank() && i.name().length() <= 80, "請填寫姓名（80 字內）");
    var scopes = db.queryForList("select scope from roles where code=?", String.class, i.role());
    Problem.check(!scopes.isEmpty(), "角色不存在");
    String scope = scopes.get(0);
    if ("BRANCH".equals(scope)) branches.requireOpen(i.branchId());
    String branch = "BRANCH".equals(scope) ? i.branchId() : null;
    if (i.id() != null) {
      var current =
          db.queryForList("select role_code from accounts where id=?", String.class, i.id());
      if (current.isEmpty()) throw new Problem(404, "帳號不存在");
      if ("HQ".equals(current.get(0)) && (!"HQ".equals(i.role()) || !i.active())) {
        db.queryForList("select id from accounts where role_code='HQ' for update");
        Problem.check(
            db.queryForObject(
                    "select count(*) from accounts where role_code='HQ' and active=true",
                    Integer.class)
                > 1,
            "必須保留至少一個啟用的總部管理帳號");
      }
      Problem.check(
          !a.id().equals(i.id()) || (i.active() && i.role().equals(a.role())), "不能停用自己或變更自己的角色");
    }
    String id = i.id() == null ? Ids.next() : i.id();
    String hash = null;
    if (i.id() == null || (i.password() != null && !i.password().isBlank())) {
      checkPassword(i.password());
      hash = encoder.encode(i.password());
    }
    if (i.id() == null)
      db.update(
          "insert into accounts(id,username,name,role_code,branch_id,active,password_hash)"
              + " values(?,?,?,?,?,?,?)",
          id,
          i.username().toLowerCase(Locale.ROOT),
          i.name(),
          i.role(),
          branch,
          i.active(),
          hash);
    else {
      db.update(
          "update accounts set username=?,name=?,role_code=?,branch_id=?,active=? where id=?",
          i.username().toLowerCase(Locale.ROOT),
          i.name(),
          i.role(),
          branch,
          i.active(),
          id);
      if (hash != null)
        db.update(
            "update accounts set password_hash=?,session_version=session_version+1 where id=?",
            hash,
            id);
    }
    audit(a, "ACCOUNT_SAVE", id);
    return new Account(id, i.username(), i.name(), i.role(), branch, i.active());
  }

  public List<Role> roles(Actor a) {
    if (!a.can("ROLE_MANAGE") && !a.can("ACCOUNT_MANAGE")) throw new Problem(403, "沒有角色管理權限");
    return db.query(
        "select * from roles order by code",
        (r, n) ->
            new Role(
                r.getString("code"),
                r.getString("name"),
                r.getString("scope"),
                new HashSet<>(
                    db.queryForList(
                        "select permission from role_permissions where role_code=?",
                        String.class,
                        r.getString("code")))));
  }

  @Transactional
  public Role saveRole(Actor a, Role r) {
    a.require("ROLE_MANAGE");
    Problem.check(a.global(), "角色管理限總部範圍");
    Problem.check(r.code() != null && r.code().matches("[A-Z][A-Z0-9_]{1,39}"), "角色代碼需為大寫英數或底線");
    Problem.check(r.name() != null && !r.name().isBlank() && r.name().length() <= 60, "請填寫角色名稱");
    Problem.check(
        r.scope() != null && Set.of("SELF", "BRANCH", "GLOBAL").contains(r.scope()), "資料範圍不正確");
    Problem.check(r.permissions() != null && PERMISSIONS.containsAll(r.permissions()), "權限不正確");
    Problem.check(!r.code().equals("HQ"), "預設總部角色保留完整管理權限，請新增自訂角色");
    if (r.scope().equals("SELF"))
      Problem.check(Set.of("ORDER_CREATE").containsAll(r.permissions()), "客人範圍僅能配置點餐功能");
    if (!r.scope().equals("GLOBAL"))
      Problem.check(
          Collections.disjoint(
              r.permissions(),
              Set.of(
                  "ACCOUNT_MANAGE", "ROLE_MANAGE", "BRANCH_MANAGE", "MENU_MANAGE", "REPORT_ALL")),
          "跨店管理功能需要總部資料範圍");
    if (!r.scope().equals("SELF")) {
      if (r.permissions().contains("ORDER_CREATE"))
        Problem.check(
            r.permissions().containsAll(Set.of("POS_ORDER", "ORDER_MANAGE")), "門市點餐需同時開放櫃台收銀與門市訂單");
      if (r.permissions().contains("POS_ORDER"))
        Problem.check(r.permissions().contains("ORDER_MANAGE"), "櫃台收銀需同時開放門市訂單");
    }
    if (r.scope().equals("GLOBAL"))
      Problem.check(!r.permissions().contains("REPORT_STORE"), "所有分店範圍請使用跨店業績功能");
    var existing = db.queryForList("select scope from roles where code=?", String.class, r.code());
    if (existing.isEmpty())
      db.update("insert into roles(code,name,scope) values(?,?,?)", r.code(), r.name(), r.scope());
    else {
      Problem.check(existing.get(0).equals(r.scope()), "既有角色不能變更資料範圍，請新增角色");
      db.update("update roles set name=? where code=?", r.name(), r.code());
    }
    db.update("delete from role_permissions where role_code=?", r.code());
    for (String p : r.permissions())
      db.update("insert into role_permissions(role_code,permission) values(?,?)", r.code(), p);
    audit(a, "ROLE_SAVE", r.code());
    return r;
  }

  public void password(Actor a, String old, String next) {
    authenticate(a.username(), old);
    checkPassword(next);
    db.update(
        "update accounts set password_hash=?,session_version=session_version+1 where id=?",
        encoder.encode(next),
        a.id());
  }

  private void checkPassword(String p) {
    Problem.check(
        p != null
            && p.length() >= 12
            && p.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72,
        "密碼至少 12 字元、最多 72 UTF-8 位元組");
  }

  private void audit(Actor a, String action, String id) {
    db.update(
        "insert into audit_log(id,actor_id,action,target_id,created_at) values(?,?,?,?,?)",
        Ids.next(),
        a.id(),
        action,
        id,
        System.currentTimeMillis());
  }
}
