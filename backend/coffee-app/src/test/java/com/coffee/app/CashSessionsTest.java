package com.coffee.app;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.coffee.identity.api.Identity;
import com.coffee.orders.api.CashSessions;
import com.coffee.orders.api.Orders;
import com.coffee.shared.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cash-sessions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class CashSessionsTest {
  @Autowired CashSessions sessions;
  @Autowired Orders orders;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired MockMvc mvc;

  Actor cashier;
  Actor manager2;

  @BeforeEach
  void reset() {
    db.update("update orders set cash_session_id=null");
    db.update("delete from cash_sessions");
    db.update("delete from audit_log where action in ('CASH_OPEN','CASH_CLOSE')");
    cashier = identity.find("cashier");
    manager2 = identity.find("manager2");
  }

  @Test
  void freshRolesIncludeCashSessionAndScopeIsEnforced() {
    assertThat(Identity.PERMISSIONS).contains("CASH_SESSION");
    assertThat(
            db.queryForList(
                "select role_code from role_permissions where permission='CASH_SESSION'"
                    + " order by role_code",
                String.class))
        .containsExactly("CASHIER", "HQ", "MANAGER");

    assertProblem(
        403,
        "只能存取所屬分店資料",
        () -> sessions.open(manager2, new CashSessions.Open("taipei", 1000, "")));
    assertProblem(
        403,
        "沒有此功能的操作權限",
        () -> sessions.open(identity.find("customer"), new CashSessions.Open("taipei", 1000, "")));
    Actor noPermission = new Actor("none", "none", "none", "CUSTOM", "GLOBAL", null, Set.of());
    assertProblem(
        403,
        "沒有此功能的操作權限",
        () -> sessions.current(noPermission, "taipei"));
  }

  @Test
  void cashSessionWritesRequireCsrfForAnAuthorizedBranch() throws Exception {
    MockHttpSession session = loginCashier();
    String body = "{\"branchId\":\"taipei\",\"openingFloat\":1000,\"note\":\"\"}";
    mvc.perform(
            post("/api/cash-sessions")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/cash-sessions")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OPEN"));
    String id =
        db.queryForObject(
            "select id from cash_sessions where branch_id='taipei' and status='OPEN'",
            String.class);
    mvc.perform(
            post("/api/cash-sessions/" + id + "/close")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedAmount\":1000,\"note\":\"\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/cash-sessions/" + id + "/close")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedAmount\":1000,\"note\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"));
  }

  @Test
  void openCashAndCloseUseOrderTotalsAndRemainBackwardCompatibleWithoutOpenSession() {
    CashSessions.Session opened =
        sessions.open(cashier, new CashSessions.Open("taipei", 2000, "早班"));
    assertThat(opened.status()).isEqualTo("OPEN");
    assertProblem(
        409,
        "此分店已有開啟中的班別，請先交班",
        () -> sessions.open(cashier, new CashSessions.Open("taipei", 0, "重複")));

    Orders.Order first = createCashOrder();
    orders.cash(cashier, first.id(), 500);
    assertThat(
            db.queryForObject(
                "select cash_session_id from orders where id=?", String.class, first.id()))
        .isEqualTo(opened.id());

    CashSessions.Session closed =
        sessions.close(cashier, opened.id(), new CashSessions.Close(2130, ""));
    assertThat(closed.cashRevenue()).isEqualTo(140);
    assertThat(closed.expectedAmount()).isEqualTo(2140);
    assertThat(closed.variance()).isEqualTo(-10);
    assertThat(closed.orderCount()).isEqualTo(1);
    assertThat(closed.note()).isEqualTo("早班");
    assertProblem(
        409,
        "此班別已交班",
        () -> sessions.close(cashier, opened.id(), new CashSessions.Close(2140, "")));
    assertProblem(
        403,
        "只能存取所屬分店資料",
        () -> sessions.close(manager2, opened.id(), new CashSessions.Close(2140, "")));

    CashSessions.Session second =
        sessions.open(cashier, new CashSessions.Open("taipei", 1000, "晚班"));
    CashSessions.Session over =
        sessions.close(cashier, second.id(), new CashSessions.Close(1100, ""));
    assertThat(over.expectedAmount()).isEqualTo(1000);
    assertThat(over.variance()).isEqualTo(100);
    assertThat(
            db.queryForList(
                "select action from audit_log where target_id in (?,?) order by action",
                String.class,
                opened.id(),
                second.id()))
        .containsExactly("CASH_CLOSE", "CASH_CLOSE", "CASH_OPEN", "CASH_OPEN");

    Orders.Order withoutSession = createCashOrder();
    orders.cash(cashier, withoutSession.id(), 200);
    assertThat(
            db.queryForObject(
                "select cash_session_id from orders where id=?", String.class, withoutSession.id()))
        .isNull();
    assertThat(sessions.current(cashier, "taipei")).isNull();
  }

  @Test
  void detailIsLiveAndHistoryUsesStableCursorWithUnassignedCashVisible() throws Exception {
    CashSessions.Page before =
        sessions.search(cashier, new CashSessions.Query("taipei", null, null, null, 2));
    Orders.Order unassigned = createCashOrder();
    orders.cash(cashier, unassigned.id(), 200);

    List<String> expectedIds = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      CashSessions.Session opened =
          sessions.open(cashier, new CashSessions.Open("taipei", 1000 + i, "班別 " + i));
      expectedIds.add(opened.id());
      if (i == 0) {
        Orders.Order assigned = createCashOrder();
        orders.cash(cashier, assigned.id(), 500);
        CashSessions.Session detail = sessions.get(cashier, opened.id());
        assertThat(detail.cashRevenue()).isEqualTo(140);
        assertThat(detail.expectedAmount()).isEqualTo(1140);
        assertThat(detail.variance()).isNull();
        assertThat(detail.closedAt()).isNull();
      }
      sessions.close(cashier, opened.id(), new CashSessions.Close(1140 + i, ""));
    }

    CashSessions.Page first =
        sessions.search(cashier, new CashSessions.Query("taipei", null, null, null, 2));
    assertThat(first.items()).hasSize(2);
    assertThat(first.nextCursor()).isNotNull();
    assertThat(first.unassignedCashRevenue() - before.unassignedCashRevenue()).isEqualTo(140);
    assertThat(first.unassignedOrderCount() - before.unassignedOrderCount()).isEqualTo(1);
    CashSessions.Page second =
        sessions.search(
            cashier, new CashSessions.Query("taipei", null, null, first.nextCursor(), 2));
    assertThat(second.nextCursor()).isNull();
    assertThat(
            java.util.stream.Stream.concat(first.items().stream(), second.items().stream())
                .map(CashSessions.Session::id))
        .containsExactlyInAnyOrderElementsOf(expectedIds);

    assertProblem(
        403,
        "只能存取所屬分店資料",
        () -> sessions.get(manager2, expectedIds.get(0)));
    assertProblem(
        403,
        "只能存取所屬分店資料",
        () ->
            sessions.search(
                manager2, new CashSessions.Query("taipei", null, null, null, 2)));
    assertProblem(
        400,
        "班別游標格式不正確",
        () ->
            sessions.search(
                cashier, new CashSessions.Query("taipei", null, null, "invalid", 2)));

    MockHttpSession httpSession = loginCashier();
    mvc.perform(get("/api/cash-sessions/" + expectedIds.get(0)).session(httpSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expectedAmount").isNumber())
        .andExpect(jsonPath("$.openedByName").isString());
    mvc.perform(
            get("/api/cash-sessions")
                .session(httpSession)
                .param("branchId", "taipei")
                .param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.nextCursor").isString())
        .andExpect(jsonPath("$.unassignedCashRevenue").isNumber());
  }

  @Test
  void concurrentOpenCreatesExactlyOneOpenSession() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Throwable>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        int number = i;
        results.add(
            executor.submit(
                () -> {
                  await(start);
                  try {
                    sessions.open(cashier, new CashSessions.Open("taipei", 1000 + number, ""));
                    return null;
                  } catch (Throwable failure) {
                    return rootCause(failure);
                  }
                }));
      }
      start.countDown();
      List<Throwable> failures = new ArrayList<>();
      for (Future<Throwable> result : results) {
        Throwable failure = result.get(5, TimeUnit.SECONDS);
        if (failure != null) failures.add(failure);
      }
      assertThat(failures)
          .singleElement()
          .isInstanceOfSatisfying(
              Problem.class,
              problem -> assertThat(problem.status).isEqualTo(409));
      assertThat(
              db.queryForObject(
                  "select count(*) from cash_sessions where branch_id='taipei' and status='OPEN'",
                  Integer.class))
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void cashAndCloseCannotLeaveAnUncountedOrderAttachedToClosedSession() throws Exception {
    CashSessions.Session opened =
        sessions.open(cashier, new CashSessions.Open("taipei", 1000, ""));
    Orders.Order order = createCashOrder();
    CountDownLatch cashHoldingBranch = new CountDownLatch(1);
    CountDownLatch closeAttempting = new CountDownLatch(1);
    CountDownLatch releaseCash = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    var transaction = new TransactionTemplate(transactionManager);
    try {
      Future<?> cash =
          executor.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        lockBranch("taipei");
                        cashHoldingBranch.countDown();
                        await(releaseCash);
                        orders.cash(cashier, order.id(), 500);
                      }));
      Future<?> close =
          executor.submit(
              () -> {
                await(cashHoldingBranch);
                closeAttempting.countDown();
                return sessions.close(
                    cashier, opened.id(), new CashSessions.Close(1140, ""));
              });
      await(closeAttempting);
      releaseCash.countDown();
      cash.get(5, TimeUnit.SECONDS);
      CashSessions.Session closed = (CashSessions.Session) close.get(5, TimeUnit.SECONDS);

      assertThat(closed.status()).isEqualTo("CLOSED");
      assertThat(closed.cashRevenue()).isEqualTo(140);
      assertThat(closed.expectedAmount()).isEqualTo(1140);
      assertThat(
              db.queryForObject(
                  "select cash_session_id from orders where id=?", String.class, order.id()))
          .isEqualTo(opened.id());
    } finally {
      releaseCash.countDown();
      executor.shutdownNow();
    }
  }

  private Orders.Order createCashOrder() {
    return orders.create(
        cashier,
        new Orders.Create(
            "taipei",
            "TAKEAWAY",
            "CASH",
            "",
            List.of(new Orders.LineInput("latte", 1, List.of("temp-hot", "sugar-none")))),
        UUID.randomUUID().toString());
  }

  private MockHttpSession loginCashier() throws Exception {
    return (MockHttpSession)
        mvc.perform(
                post("/api/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"cashier@coffee.local\","
                            + "\"password\":\"CoffeeDemo!2026\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession();
  }

  private void lockBranch(String branchId) {
    db.queryForObject("select id from branches where id=? for update", String.class, branchId);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable result = failure;
    while (result.getCause() != null) result = result.getCause();
    return result;
  }

  private static void assertProblem(
      int status, String message, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(status);
              assertThat(problem).hasMessage(message);
            });
  }
}
