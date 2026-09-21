package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffee.branches.api.Branches;
import com.coffee.branches.api.Branches.Hours;
import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.orders.api.Orders.Create;
import com.coffee.orders.api.Orders.LineInput;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:branch-hours-ordering;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Transactional
class BranchHoursOrderingTest {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  @Autowired Branches branches;
  @Autowired Orders orders;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;

  Actor customer;
  Actor manager;
  Actor headquarters;

  @BeforeEach
  void reset() {
    db.update("delete from branch_hours");
    customer = identity.find("customer");
    manager = identity.find("manager");
    headquarters = identity.find("hq");
  }

  @Test
  void customerIsBlockedWithTodayHoursButPointOfSaleRemainsAvailable() {
    branches.saveHours(headquarters, "taipei", closedToday());

    assertThatThrownBy(() -> create(customer, "customer-closed-" + UUID.randomUUID()))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(400);
              assertThat(problem.getMessage()).contains("分店目前未營業（今日營業時間");
            });

    assertThat(create(manager, "pos-closed-" + UUID.randomUUID()).branchId())
        .isEqualTo("taipei");
  }

  @Test
  void closedDayUsesTheDedicatedMessageAndOpenCustomerOrderSucceeds() {
    int tomorrow = currentDay() == 7 ? 1 : currentDay() + 1;
    branches.saveHours(headquarters, "taipei", List.of(new Hours(tomorrow, 540, 1260)));
    assertThatThrownBy(() -> create(customer, "customer-no-hours-" + UUID.randomUUID()))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> assertThat(problem.getMessage()).isEqualTo("分店今日未營業"));

    branches.saveHours(headquarters, "taipei", List.of());
    assertThat(create(customer, "customer-open-" + UUID.randomUUID()).accountId())
        .isEqualTo(customer.id());
  }

  @Test
  void idempotentReplayAndExistingOrderOperationsStillWorkAfterClosing() {
    String key = "customer-replay-" + UUID.randomUUID();
    var original = create(customer, key);
    branches.saveHours(headquarters, "taipei", closedToday());

    assertThat(create(customer, key).id()).isEqualTo(original.id());
    var paid = orders.cash(manager, original.id(), original.total());
    assertThat(paid.status()).isEqualTo("PAID");
    assertThat(orders.transition(manager, original.id(), "PREPARING").status())
        .isEqualTo("PREPARING");
  }

  @Test
  void branchListAddsOpenNowKeepsClosedActiveAndStillHidesInactive() throws Exception {
    int tomorrow = currentDay() == 7 ? 1 : currentDay() + 1;
    branches.saveHours(headquarters, "taipei", List.of(new Hours(tomorrow, 540, 1260)));
    db.update("update branches set active=false where id='banqiao'");

    mvc.perform(get("/api/branches").session(session(customer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == 'taipei' && @.openNow == false)]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.id == 'banqiao')]").isEmpty())
        .andExpect(jsonPath("$[0].openNow").isBoolean());
  }

  private Orders.Order create(Actor actor, String key) {
    return orders.create(
        actor,
        new Create(
            "taipei",
            "TAKEAWAY",
            "CASH",
            "營業時間測試",
            List.of(new LineInput("latte", 1, List.of("temp-hot", "sugar-none")))),
        key);
  }

  private List<Hours> closedToday() {
    var now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(TAIPEI);
    int minute = now.getHour() * 60 + now.getMinute();
    int open = minute < 1438 ? minute + 1 : 0;
    return List.of(new Hours(now.getDayOfWeek().getValue(), open, open + 1));
  }

  private int currentDay() {
    return Instant.ofEpochMilli(System.currentTimeMillis())
        .atZone(TAIPEI)
        .getDayOfWeek()
        .getValue();
  }

  private MockHttpSession session(Actor actor) {
    var session = new MockHttpSession();
    session.setAttribute("ACCOUNT_ID", actor.id());
    session.setAttribute("ACCOUNT_VERSION", identity.sessionVersion(actor.id()));
    return session;
  }
}
