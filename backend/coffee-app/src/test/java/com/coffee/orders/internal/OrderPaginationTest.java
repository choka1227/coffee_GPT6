package com.coffee.orders.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.coffee.audit.api.Audit;
import com.coffee.branches.api.Branches;
import com.coffee.catalog.api.Catalog;
import com.coffee.orders.api.Orders;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.lang.reflect.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class OrderPaginationTest {
  private JdbcTemplate db;
  private OrderService orders;
  private AtomicInteger statements;

  @BeforeEach
  void setUp() {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:mem:order-page-"
            + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    statements = new AtomicInteger();
    db = new JdbcTemplate(counting(source, statements));
    db.execute("create table branches(id varchar(36) primary key,name varchar(80) not null)");
    db.execute(
        "create table orders(id varchar(20) primary key,branch_id varchar(36),account_id varchar(36),"
            + "status varchar(24),fulfillment varchar(20),payment_method varchar(10),total integer,"
            + "note varchar(200),created_at bigint,paid_at bigint,tendered integer,change_amount integer)");
    db.execute(
        "create table order_items(id varchar(36) primary key,order_id varchar(20),product_id varchar(36),"
            + "name varchar(80),category varchar(40),unit_price integer,quantity integer,"
            + "temperature varchar(12),sugar varchar(12),options_price integer)");
    db.execute(
        "create table order_item_options(id varchar(36) primary key,order_item_id varchar(36),"
            + "group_name varchar(80),option_name varchar(80),price_delta integer)");
    db.update("insert into branches values('taipei','台北店'),('taichung','台中店')");
    orders =
        new OrderService(
            db, mock(Catalog.class), mock(Branches.class), mock(Audit.class));
  }

  @Test
  void cursorValidationAndLikeEscapingAreStable() {
    String cursor = OrderService.encodeCursor(123L, "ORD-1:PART");
    assertThat(OrderService.decodeCursor(cursor))
        .isEqualTo(new OrderService.Cursor(123L, "ORD-1:PART"));
    assertThat(OrderService.escapeLike("100%_c\\d")).isEqualTo("100\\%\\_c\\\\d");
    for (String invalid : List.of("", "abc", ":", "123:", ":x", "x:id", "1:123456789012345678901")) {
      assertThatThrownBy(() -> OrderService.decodeCursor(invalid))
          .isInstanceOf(Problem.class)
          .hasMessage("查詢游標格式不正確");
    }
  }

  @Test
  void pagesWithoutDuplicatesAndUsesThreeQueriesRegardlessOfPageSize() {
    for (int i = 0; i < 25; i++) seed(i, "owner", "taipei", 1_000L + i, "PAID", "Latte");
    Actor manager = actor("manager", "GLOBAL", null, "ORDER_MANAGE");

    statements.set(0);
    Orders.Page first = orders.page(manager, query(null, null, null, 5));
    assertThat(first.items()).hasSize(5);
    assertThat(first.nextCursor()).isNotNull();
    assertThat(statements).hasValue(3);

    Set<String> ids = new HashSet<>();
    String cursor = null;
    do {
      Orders.Page page = orders.page(manager, query(null, null, cursor, 10));
      assertThat(page.items()).allSatisfy(order -> assertThat(ids.add(order.id())).isTrue());
      cursor = page.nextCursor();
    } while (cursor != null);
    assertThat(ids).hasSize(25);

    statements.set(0);
    assertThat(orders.page(manager, query("CANCELLED", null, null, 50)).items()).isEmpty();
    assertThat(statements).hasValue(1);
  }

  @Test
  void scopeAndFiltersCannotBeBypassedByCursorOrBranchParameter() {
    seed(1, "alice", "taipei", 2_000L, "PAID", "百分比 100% Latte");
    seed(2, "bob", "taipei", 2_000L, "PAID", "Other");
    seed(3, "alice", "taichung", 2_001L, "READY", "Other");

    Actor alice = actor("alice", "SELF", null);
    assertThat(orders.page(alice, query(null, null, null, 50)).items())
        .extracting(Orders.Order::accountId)
        .containsOnly("alice");
    assertThat(orders.page(alice, query(null, "taipei", null, 50)).items())
        .extracting(Orders.Order::branchId)
        .containsOnly("taipei");
    assertThat(orders.page(alice, new Orders.Query(null, null, 2_000L, 2_000L, "%", null, 50)).items())
        .extracting(Orders.Order::id)
        .containsExactly("ORD-01");

    Actor branch = actor("staff", "BRANCH", "taipei", "ORDER_MANAGE");
    assertThatThrownBy(() -> orders.page(branch, query(null, "taichung", null, 50)))
        .isInstanceOf(Problem.class)
        .hasMessage("只能存取所屬分店資料");
    assertThatThrownBy(
            () -> orders.page(actor("staff", "BRANCH", "taipei"), query(null, null, null, 50)))
        .isInstanceOf(Problem.class)
        .hasMessage("沒有此功能的操作權限");
  }

  private Orders.Query query(String status, String branchId, String cursor, int limit) {
    return new Orders.Query(status, branchId, null, null, null, cursor, limit);
  }

  private Actor actor(String id, String scope, String branchId, String... permissions) {
    return new Actor(id, id, id, "test", scope, branchId, Set.of(permissions));
  }

  private void seed(
      int number, String account, String branch, long createdAt, String status, String itemName) {
    String orderId = "ORD-" + String.format("%02d", number);
    String itemId = "ITEM-" + number;
    db.update(
        "insert into orders values(?,?,?,?,?,'CASH',100,'',?,null,null,null)",
        orderId,
        branch,
        account,
        status,
        "TAKEAWAY",
        createdAt);
    db.update(
        "insert into order_items values(?,?,?,?,?,100,1,null,null,5)",
        itemId,
        orderId,
        "latte",
        itemName,
        "coffee");
    db.update("insert into order_item_options values(?,?,?,'Extra',5)", "OPT-" + number, itemId, "加料");
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
                      if ("prepareStatement".equals(connectionMethod.getName())) statements.incrementAndGet();
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
