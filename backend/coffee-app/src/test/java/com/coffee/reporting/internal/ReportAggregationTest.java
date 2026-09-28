package com.coffee.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReportAggregationTest {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
  private static final String MONTH = "2024-02";

  private JdbcTemplate db;
  private ReportService reports;
  private AtomicInteger statements;

  @BeforeEach
  void setUp() {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:mem:report-aggregation-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    statements = new AtomicInteger();
    db = new JdbcTemplate(counting(source, statements));
    db.execute(
        "create table branches(id varchar(36) primary key,name varchar(80) not null,monthly_target"
            + " integer not null)");
    db.execute(
        "create table orders(id varchar(20) primary key,branch_id varchar(36) not null,"
            + "total integer not null,discount_amount integer not null,paid_at bigint,"
            + "fulfillment varchar(20) not null,payment_method varchar(10) not null)");
    db.execute(
        "create table order_items(id varchar(36) primary key,order_id varchar(20) not"
            + " null,product_id varchar(36) not null,name varchar(80) not null,category varchar(40)"
            + " not null,unit_price integer not null,unit_cost integer not null,quantity integer"
            + " not null,options_price integer not null,options_cost integer not null)");
    db.update(
        "insert into branches values"
            + "('alpha','Alpha',1000),('beta','Beta',1000),('zero','Zero',0)");

    seed(
        "A-1",
        "alpha",
        100,
        10,
        "2024-02-01T00:30",
        "TAKEAWAY",
        "CASH",
        "latte",
        "Latte",
        "coffee",
        90,
        30,
        1,
        10,
        10);
    seed(
        "A-2",
        "alpha",
        200,
        0,
        "2024-02-01T23:30",
        "DINE_IN",
        "ECPAY",
        "latte",
        "Latte",
        "coffee",
        90,
        30,
        2,
        10,
        10);
    seed(
        "B-1",
        "beta",
        400,
        0,
        "2024-02-15T23:30",
        "TAKEAWAY",
        "ECPAY",
        "cake",
        "Cake",
        "dessert",
        200,
        80,
        2,
        0,
        0);
    seed(
        "B-2",
        "beta",
        300,
        20,
        "2024-02-29T00:30",
        "DINE_IN",
        "CASH",
        "tea",
        "Tea",
        "tea",
        300,
        90,
        1,
        0,
        0);
    reports = new ReportService(db);
  }

  @Test
  void completeOutputPreservesBucketsOrderingTypesAndTaipeiBoundaries() {
    statements.set(0);

    Map<String, Object> actual = reports.report(global(), MONTH, null);

    assertThat(actual).isEqualTo(expected());
    assertThat(statements).hasValue(7);
  }

  @Test
  void queryCountIsSevenForThirtyAndThreeHundredOrders() {
    for (int i = 4; i < 30; i++) seedExtra(i);
    statements.set(0);
    reports.report(global(), MONTH, null);
    assertThat(statements).hasValue(7);

    for (int i = 30; i < 300; i++) seedExtra(i);
    statements.set(0);
    reports.report(global(), MONTH, null);
    assertThat(statements).hasValue(7);
  }

  @Test
  void emptyMonthKeepsZeroTotalsAndEveryEmptyBucket() {
    statements.set(0);

    Map<String, Object> report = reports.report(global(), "2024-03", null);

    assertThat(report)
        .containsEntry("revenue", 0L)
        .containsEntry("discount", 0L)
        .containsEntry("orders", 0L)
        .containsEntry("averageOrder", 0L)
        .containsEntry("grossMargin", 0.0)
        .containsEntry("cashOrders", 0L)
        .containsEntry("onlineOrders", 0L)
        .containsEntry("takeawayOrders", 0L);
    assertThat((List<?>) report.get("daily")).hasSize(31);
    assertThat((List<?>) report.get("hourly")).hasSize(24);
    assertThat((List<Map<String, Object>>) report.get("branches"))
        .allSatisfy(
            row -> {
              assertThat(row.get("revenue")).isEqualTo(0L);
              assertThat(row.get("orders")).isEqualTo(0);
            });
    assertThat(statements).hasValue(7);
  }

  @Test
  void branchScopeCannotSeeOrRequestAnotherBranch() {
    Actor alpha = branchActor("alpha");

    Map<String, Object> report = reports.report(alpha, MONTH, null);

    assertThat(report).containsEntry("revenue", 300L).containsEntry("orders", 2L);
    assertThat((List<Map<String, Object>>) report.get("branches"))
        .singleElement()
        .satisfies(row -> assertThat(row.get("id")).isEqualTo("alpha"));
    assertThatThrownBy(() -> reports.report(alpha, MONTH, "beta"))
        .isInstanceOf(Problem.class)
        .hasMessage("只能存取所屬分店資料");
  }

  private Map<String, Object> expected() {
    List<Map<String, Object>> daily = new ArrayList<>();
    for (int day = 1; day <= 29; day++) {
      long revenue = day == 1 ? 300L : day == 15 ? 400L : day == 29 ? 300L : 0L;
      int orders = day == 1 ? 2 : day == 15 || day == 29 ? 1 : 0;
      daily.add(Map.of("day", String.format("%02d", day), "revenue", revenue, "orders", orders));
    }

    List<Map<String, Object>> hourly = new ArrayList<>();
    for (int hour = 0; hour < 24; hour++) {
      hourly.add(
          Map.of(
              "hour", String.format("%02d:00", hour), "orders", hour == 0 || hour == 23 ? 2L : 0L));
    }

    List<Map<String, Object>> products =
        List.of(
            row("latte", "Latte", "coffee", 3L, 300L, 120L),
            row("cake", "Cake", "dessert", 2L, 400L, 160L),
            row("tea", "Tea", "tea", 1L, 300L, 90L));
    Map<String, Long> categories = new LinkedHashMap<>();
    categories.put("coffee", 300L);
    categories.put("dessert", 400L);
    categories.put("tea", 300L);

    return Map.ofEntries(
        Map.entry("month", MONTH),
        Map.entry("today", LocalDate.now(TAIPEI).toString()),
        Map.entry("revenue", 1000L),
        Map.entry("discount", 30L),
        Map.entry("orders", 4L),
        Map.entry("averageOrder", 250L),
        Map.entry("quantity", 6L),
        Map.entry("grossProfit", 630L),
        Map.entry("grossMargin", 63.0),
        Map.entry("daily", daily),
        Map.entry("products", products),
        Map.entry("topToday", List.of()),
        Map.entry(
            "branches",
            List.of(
                branch("alpha", "Alpha", 300L, 2, 1000, 30.0),
                branch("beta", "Beta", 700L, 2, 1000, 70.0),
                branch("zero", "Zero", 0L, 0, 0, 0.0))),
        Map.entry("categories", categories),
        Map.entry("hourly", hourly),
        Map.entry("cashOrders", 2L),
        Map.entry("onlineOrders", 2L),
        Map.entry("takeawayOrders", 2L));
  }

  private Map<String, Object> row(
      String id, String name, String category, long quantity, long revenue, long cost) {
    return Map.of(
        "id", id,
        "name", name,
        "category", category,
        "quantity", quantity,
        "revenue", revenue,
        "cost", cost);
  }

  private Map<String, Object> branch(
      String id, String name, long revenue, int orders, int target, double achievement) {
    return Map.of(
        "id", id,
        "name", name,
        "revenue", revenue,
        "orders", orders,
        "target", target,
        "achievement", achievement);
  }

  private Actor global() {
    return new Actor("hq", "hq", "HQ", "test", "GLOBAL", null, Set.of("REPORT_ALL"));
  }

  private Actor branchActor(String branch) {
    return new Actor("staff", "staff", "Staff", "test", "BRANCH", branch, Set.of("REPORT_STORE"));
  }

  private void seedExtra(int number) {
    seed(
        "X-" + number,
        number % 2 == 0 ? "alpha" : "beta",
        100,
        0,
        "2024-02-10T12:00",
        "DINE_IN",
        "CASH",
        "extra",
        "Extra",
        "coffee",
        100,
        50,
        1,
        0,
        0);
  }

  private void seed(
      String id,
      String branch,
      int total,
      int discount,
      String paidAt,
      String fulfillment,
      String method,
      String productId,
      String name,
      String category,
      int price,
      int cost,
      int quantity,
      int optionsPrice,
      int optionsCost) {
    long epoch = LocalDateTime.parse(paidAt).atZone(TAIPEI).toInstant().toEpochMilli();
    db.update(
        "insert into orders values(?,?,?,?,?,?,?)",
        id,
        branch,
        total,
        discount,
        epoch,
        fulfillment,
        method);
    db.update(
        "insert into order_items values(?,?,?,?,?,?,?,?,?,?)",
        "ITEM-" + id,
        id,
        productId,
        name,
        category,
        price,
        cost,
        quantity,
        optionsPrice,
        optionsCost);
  }

  private static DataSource counting(DataSource delegate, AtomicInteger statements) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(method, delegate, args);
              if ("getConnection".equals(method.getName())) {
                Connection connection = (Connection) result;
                return Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (connectionProxy, connectionMethod, connectionArgs) -> {
                      if ("prepareStatement".equals(connectionMethod.getName())) {
                        statements.incrementAndGet();
                      }
                      return invoke(connectionMethod, connection, connectionArgs);
                    });
              }
              return result;
            });
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }
}
