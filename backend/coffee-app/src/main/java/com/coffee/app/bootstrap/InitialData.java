package com.coffee.app.bootstrap;

import com.coffee.identity.api.Identity;
import com.coffee.shared.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InitialData implements ApplicationRunner {
  private final JdbcTemplate db;
  private final boolean demo;
  private final String username, password, demoPassword;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  public InitialData(
      JdbcTemplate db,
      @Value("${app.seed-demo:false}") boolean demo,
      @Value("${app.bootstrap-username:}") String username,
      @Value("${app.bootstrap-password:}") String password,
      @Value("${app.demo-password:}") String demoPassword) {
    this.db = db;
    this.demo = demo;
    this.username = username;
    this.password = password;
    this.demoPassword = demoPassword;
  }

  @Transactional
  public void run(ApplicationArguments args) {
    if (db.queryForObject("select count(*) from roles", Integer.class) == 0) {
      role("CUSTOMER", "客人", "SELF", List.of("ORDER_CREATE"));
      role("CASHIER", "收銀員", "BRANCH", List.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE"));
      role(
          "MANAGER",
          "店長",
          "BRANCH",
          List.of("ORDER_CREATE", "POS_ORDER", "ORDER_MANAGE", "REPORT_STORE"));
      role("HQ", "總部人員", "GLOBAL", Identity.PERMISSIONS);
    }
    if (db.queryForObject("select count(*) from accounts", Integer.class) > 0) return;
    if (!demo) {
      Problem.check(
          username.matches("[A-Za-z0-9@._+-]{3,100}")
              && password.length() >= 12
              && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72,
          "首次啟動需設定 BOOTSTRAP_USERNAME 與至少 12 字元的 BOOTSTRAP_PASSWORD");
      account("hq", username, "總部管理員", "HQ", null, encoder.encode(password));
      return;
    }
    String hash = encoder.encode(demoPassword);
    db.update(
        "insert into branches values('taipei','台北・中山店','台北市中山區中山北路二段 26"
            + " 號','02-2521-0826',true,450000)");
    db.update(
        "insert into branches values('banqiao','板橋・江子翠店','新北市板橋區文化路二段 128"
            + " 號','02-2258-0826',true,360000)");
    db.update(
        "insert into branches values('taichung','台中・勤美店','台中市西區公益路 68"
            + " 號','04-2301-0826',true,320000)");
    account("hq", "hq@coffee.local", "總部管理員", "HQ", null, hash);
    account("manager", "manager@coffee.local", "中山店・店長", "MANAGER", "taipei", hash);
    account("cashier", "cashier@coffee.local", "中山店・收銀員", "CASHIER", "taipei", hash);
    account("customer", "customer@coffee.local", "咖啡好友", "CUSTOMER", null, hash);
    account("manager2", "manager2@coffee.local", "江子翠店・店長", "MANAGER", "banqiao", hash);
    String[][] ps = {
      {"latte", "經典拿鐵", "濃縮咖啡 × 香醇鮮乳", "經典咖啡", "140", "48", "latte", "人氣首選"},
      {"americano", "醇黑美式", "中深焙・堅果與黑巧克力尾韻", "經典咖啡", "100", "25", "latte", ""},
      {"flatwhite", "澳白咖啡", "雙份濃縮・細緻綿密奶泡", "經典咖啡", "150", "50", "latte", ""},
      {"caramel", "海鹽焦糖拿鐵", "手熬焦糖・海鹽・鮮乳", "風味特調", "170", "58", "latte", "招牌"},
      {"vanilla", "香草拿鐵", "馬達加斯加香草・柔和甜香", "風味特調", "160", "52", "latte", ""},
      {"milk", "香醇熱鮮乳", "溫潤奶香・無咖啡因", "茶與其他", "100", "40", "latte", ""},
      {"croissant", "法式奶油可頌", "酥脆層次・法國發酵奶油", "手作烘焙", "90", "35", "pastry", "每日烘焙"},
      {"almond", "杏仁奶油可頌", "杏仁奶油餡・香脆杏仁片", "手作烘焙", "120", "45", "pastry", ""}
    };
    for (int i = 0; i < ps.length; i++) {
      var p = ps[i];
      db.update(
          "insert into products values(?,?,?,?,?,?,?,?,true,?)",
          p[0],
          p[1],
          p[2],
          p[3],
          Integer.parseInt(p[4]),
          Integer.parseInt(p[5]),
          p[6],
          p[7],
          i);
    }
    // Development fixture only: previous + current month, deterministic quantities, paid orders.
    var today = LocalDate.now(ZoneId.of("Asia/Taipei"));
    var first = today.withDayOfMonth(1).minusMonths(1);
    Random random = new Random(26);
    for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
      for (String branch : List.of("taipei", "banqiao", "taichung")) {
        int n = branch.equals("taipei") ? 18 : 12;
        for (int i = 0; i < n; i++) {
          String[] p = ps[random.nextInt(ps.length)];
          int qty = 1 + random.nextInt(3), total = Integer.parseInt(p[4]) * qty;
          String id = Ids.order();
          long time =
              day.atTime(8 + i % 12, random.nextInt(60))
                  .atZone(ZoneId.of("Asia/Taipei"))
                  .toInstant()
                  .toEpochMilli();
          if (time > System.currentTimeMillis()) continue;
          db.update(
              "insert into"
                  + " orders(id,branch_id,account_id,status,fulfillment,payment_method,total,note,created_at,paid_at,idempotency_key,request_hash)"
                  + " values(?,?,?,'COMPLETED',?,?,?,?,?,?,?,?)",
              id,
              branch,
              "customer",
              i % 3 == 0 ? "DINE_IN" : "TAKEAWAY",
              i % 2 == 0 ? "CASH" : "ECPAY",
              total,
              "開發示範訂單",
              time,
              time,
              id,
              "demo");
          db.update(
              "insert into order_items values(?,?,?,?,?,?,?,?,?,?)",
              Ids.next(),
              id,
              p[0],
              p[1],
              p[3],
              Integer.parseInt(p[4]),
              Integer.parseInt(p[5]),
              qty,
              p[3].equals("手作烘焙") ? "不適用" : "熱",
              p[3].equals("手作烘焙") ? "不適用" : "無糖");
        }
      }
    }
  }

  private void role(String code, String name, String scope, List<String> permissions) {
    db.update("insert into roles values(?,?,?)", code, name, scope);
    for (String p : permissions) db.update("insert into role_permissions values(?,?)", code, p);
  }

  private void account(
      String id, String username, String name, String role, String branch, String hash) {
    db.update(
        "insert into accounts(id,username,name,role_code,branch_id,active,password_hash)"
            + " values(?,?,?,?,?,true,?)",
        id,
        username.toLowerCase(Locale.ROOT),
        name,
        role,
        branch,
        hash);
  }
}
