package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffee.branches.api.Branches;
import com.coffee.branches.api.Branches.DayOverride;
import com.coffee.branches.api.Branches.Hours;
import com.coffee.branches.api.Branches.OpenState;
import com.coffee.branches.internal.BranchService;
import com.coffee.branches.internal.BranchService.OpenWindow;
import com.coffee.branches.internal.BranchService.Resolver;
import com.coffee.identity.api.Identity;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:branch-last-order;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class BranchLastOrderTest {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
  private static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

  @Autowired Branches branches;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;

  Actor hq;

  @BeforeEach
  void resetDatabase() {
    db.update("delete from branch_day_override_hours");
    db.update("delete from branch_day_overrides");
    db.update("delete from branch_hours");
    db.update("update branches set last_order_minutes=0");
    db.update(
        "delete from role_permissions where role_code='MANAGER' and permission='BRANCH_MANAGE'");
    hq = identity.find("hq");
  }

  record ScheduleCase(String name, Resolver resolver, IntPredicate expectedOpen) {
    @Override
    public String toString() {
      return name;
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("schedules")
  void windowAtMatchesThePreviousRulesForEveryMinute(ScheduleCase schedule) {
    for (int minute = 0; minute < 1440; minute++) {
      boolean open =
          !(BranchService.windowAt(schedule.resolver(), at(MONDAY, minute))
              instanceof OpenWindow.NotOpen);
      assertThat(open)
          .as("%s at minute %s", schedule.name(), minute)
          .isEqualTo(schedule.expectedOpen().test(minute));
    }
  }

  @Test
  void overnightWindowReportsMinutesUntilTheNextDayClose() {
    var window = BranchService.windowAt(weekly(List.of(new Hours(1, 1320, 120))), at(MONDAY, 1380));

    assertThat(window).isEqualTo(new OpenWindow.ClosesIn(180));
  }

  @ParameterizedTest(name = "L=0 keeps open and orderable equal for {0}")
  @MethodSource("schedules")
  void zeroLastOrderKeepsOrderableEqualToOpenForEveryMinute(ScheduleCase schedule) {
    for (int minute = 0; minute < 1440; minute++) {
      OpenWindow window = BranchService.windowAt(schedule.resolver(), at(MONDAY, minute));
      OpenState state = BranchService.state(window, 0);
      assertThat(state.orderableNow()).isEqualTo(state.openNow());
    }
  }

  @Test
  void crossMidnightAndShortPeriodsUseTheSameCutoffRule() {
    var overnight = weekly(List.of(new Hours(1, 1320, 120)));
    assertThat(BranchService.state(BranchService.windowAt(overnight, at(MONDAY, 1515)), 30))
        .isEqualTo(new OpenState(true, true, 15));
    assertThat(BranchService.state(BranchService.windowAt(overnight, at(MONDAY, 1545)), 30))
        .isEqualTo(new OpenState(true, false, null));

    var shortPeriod = weekly(List.of(new Hours(1, 600, 620)));
    for (int minute = 600; minute < 620; minute++) {
      assertThat(BranchService.state(BranchService.windowAt(shortPeriod, at(MONDAY, minute)), 30))
          .isEqualTo(new OpenState(true, false, null));
    }
  }

  @Test
  void cutoffMessagesPointToTheNextOrderablePeriodToday() {
    branches.saveHours(hq, "taipei", List.of(new Hours(1, 540, 720), new Hours(1, 780, 1080)), 15);
    branches.requireOrderable("taipei", at(MONDAY, 704));
    assertProblem(
        "分店已停止接單（最後點餐時間 11:45），請於今日 13:00 起的營業時段再下單",
        () -> branches.requireOrderable("taipei", at(MONDAY, 710)));
    branches.requireOrderable("taipei", at(MONDAY, 810));
    assertProblem(
        "分店已停止接單（最後點餐時間 17:45），請於下一個營業時段再下單",
        () -> branches.requireOrderable("taipei", at(MONDAY, 1070)));

    branches.saveHours(hq, "taipei", List.of(new Hours(1, 540, 720), new Hours(1, 780, 790)), 15);
    assertProblem(
        "分店已停止接單（最後點餐時間 11:45），請於下一個營業時段再下單",
        () -> branches.requireOrderable("taipei", at(MONDAY, 710)));
  }

  @Test
  void overridePeriodsAndOvernightMessagesNeverClaimTomorrow() {
    branches.saveHours(hq, "taipei", List.of(new Hours(1, 1320, 120)), 30);
    assertProblemWithoutTomorrow(
        "分店已停止接單（最後點餐時間 01:30），請於下一個營業時段再下單",
        () -> branches.requireOrderable("taipei", at(MONDAY, 1545)));

    branches.saveHours(hq, "taipei", List.of(new Hours(1, 540, 720)), 15);
    branches.saveOverride(
        hq,
        "taipei",
        new DayOverride(
            20260921, false, "臨時調整", List.of(new Hours(1, 540, 720), new Hours(1, 840, 1020))));
    assertProblemWithoutTomorrow(
        "分店已停止接單（最後點餐時間 11:45），請於今日 14:00 起的營業時段再下單",
        () -> branches.requireOrderable("taipei", at(MONDAY, 710)));
  }

  @Test
  void overnightWindowUsesTheLastOrderValueFromTheOwningDay() {
    branches.saveHours(hq, "taipei", List.of(new Hours(1, 1320, 120)), 15);
    branches.saveOverride(
        hq, "taipei", new DayOverride(20260921, false, "", List.of(), 60));

    assertThat(branches.stateAt("taipei", at(MONDAY, 1530)))
        .isEqualTo(new OpenState(true, false, null));

    branches.deleteOverride(hq, "taipei", 20260921);
    branches.saveOverride(
        hq, "taipei", new DayOverride(20260922, false, "", List.of(), 120));
    assertThat(branches.stateAt("taipei", at(MONDAY, 1530)))
        .isEqualTo(new OpenState(true, true, 15));
  }

  @Test
  void singleAndListStatesAgreeWithDailyLastOrderWithoutExtraStateQueries() {
    branches.saveHours(hq, "taipei", List.of(new Hours(1, 540, 720)), 0);
    branches.saveOverride(
        hq, "taipei", new DayOverride(20260921, false, "", List.of(), 30));
    long at = at(MONDAY, 690);

    OpenState single = branches.stateAt("taipei", at);
    assertThat(branches.stateAt(List.of("taipei"), at)).containsEntry("taipei", single);
    assertProblem(
        "分店已停止接單（最後點餐時間 11:30），請於下一個營業時段再下單",
        () -> branches.requireOrderable("taipei", at));
  }

  @Test
  void savingLastOrderIsValidatedTransactionalAndLegacyCallsPreserveIt() {
    var original = List.of(new Hours(1, 540, 720));
    branches.saveHours(hq, "taipei", original, 15);
    branches.saveHours(hq, "taipei", List.of(new Hours(1, 600, 780)));
    assertThat(branches.lastOrderMinutes("taipei")).isEqualTo(15);

    assertProblem("最後點餐提前時間需為 0–120 分鐘", () -> branches.saveHours(hq, "taipei", original, -1));
    assertProblem("最後點餐提前時間需為 0–120 分鐘", () -> branches.saveHours(hq, "taipei", original, 121));
    assertProblem(
        "同一天的營業時段不能重疊",
        () ->
            branches.saveHours(
                hq, "taipei", List.of(new Hours(1, 540, 720), new Hours(1, 600, 780)), 30));
    assertThat(branches.lastOrderMinutes("taipei")).isEqualTo(15);

    var saved = branches.requireOpen("taipei");
    branches.save(
        hq,
        new Branches.Branch(
            saved.id(),
            "重新命名",
            saved.address(),
            saved.phone(),
            saved.active(),
            saved.monthlyTarget()));
    assertThat(branches.lastOrderMinutes("taipei")).isEqualTo(15);
  }

  @Test
  void hoursEndpointPreservesOmittedValueRejectsInvalidInputAndBlocksUnauthorizedWrites()
      throws Exception {
    String hours = "[{\"dayOfWeek\":1,\"openMinute\":540,\"closeMinute\":720}]";
    mvc.perform(
            put("/api/branches/taipei/hours")
                .session(session("hq"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":" + hours + ",\"lastOrderMinutes\":15}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastOrderMinutes").value(15));
    mvc.perform(
            put("/api/branches/taipei/hours")
                .session(session("hq"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":" + hours + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastOrderMinutes").value(15));

    for (String invalid : List.of("-1", "121", "\"不是整數\"")) {
      mvc.perform(
              put("/api/branches/taipei/hours")
                  .session(session("hq"))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"hours\":" + hours + ",\"lastOrderMinutes\":" + invalid + "}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("最後點餐提前時間需為 0–120 分鐘"));
      assertThat(branches.lastOrderMinutes("taipei")).isEqualTo(15);
    }

    for (String account : List.of("cashier", "customer")) {
      mvc.perform(
              put("/api/branches/taipei/hours")
                  .session(session(account))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"hours\":" + hours + ",\"lastOrderMinutes\":30}"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            put("/api/branches/taipei/hours")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":" + hours + ",\"lastOrderMinutes\":30}"))
        .andExpect(status().isUnauthorized());

    db.update(
        "insert into role_permissions(role_code,permission) values('MANAGER','BRANCH_MANAGE')");
    mvc.perform(
            put("/api/branches/taipei/hours")
                .session(session("manager"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":" + hours + ",\"lastOrderMinutes\":30}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("此功能限總部範圍"));
    assertThat(branches.lastOrderMinutes("taipei")).isEqualTo(15);
  }

  @Test
  void customersAreBlockedAfterCutoffWhileCashiersMayStillCreateOrders() throws Exception {
    var now = java.time.Instant.now().atZone(TAIPEI);
    int minute = now.getHour() * 60 + now.getMinute();
    int close = Math.floorMod(minute + 60, 1440);
    if (close == 0) close = 1440;
    branches.saveHours(
        hq, "taipei", List.of(new Hours(now.getDayOfWeek().getValue(), minute, close)), 60);
    String body =
        "{\"branchId\":\"taipei\",\"fulfillment\":\"TAKEAWAY\","
            + "\"paymentMethod\":\"CASH\",\"note\":\"\",\"items\":[{"
            + "\"productId\":\"latte\",\"quantity\":1,"
            + "\"optionIds\":[\"temp-hot\",\"sugar-none\"]}]}";

    mvc.perform(
            post("/api/orders")
                .session(session("customer"))
                .with(csrf())
                .header("Idempotency-Key", "last-order-customer-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已停止接單")));
    mvc.perform(
            post("/api/orders")
                .session(session("cashier"))
                .with(csrf())
                .header("Idempotency-Key", "last-order-cashier-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  private static Stream<ScheduleCase> schedules() {
    var overridePeriods =
        Map.of(MONDAY, new DayOverride(20260921, false, "臨時調整", List.of(new Hours(1, 600, 700))));
    var overrideClosed = Map.of(MONDAY, new DayOverride(20260921, true, "公休", List.of()));
    return Stream.of(
        new ScheduleCase(
            "weekly single period",
            weekly(List.of(new Hours(1, 540, 1260))),
            minute -> minute >= 540 && minute < 1260),
        new ScheduleCase(
            "weekly split periods",
            weekly(List.of(new Hours(1, 540, 720), new Hours(1, 780, 1080))),
            minute -> (minute >= 540 && minute < 720) || (minute >= 780 && minute < 1080)),
        new ScheduleCase(
            "weekly overnight period",
            weekly(List.of(new Hours(7, 1320, 120), new Hours(1, 1320, 120))),
            minute -> minute < 120 || minute >= 1320),
        new ScheduleCase(
            "override periods",
            resolver(List.of(new Hours(1, 540, 1260)), overridePeriods),
            minute -> minute >= 600 && minute < 700),
        new ScheduleCase(
            "override closed",
            resolver(List.of(new Hours(1, 540, 1260)), overrideClosed),
            minute -> false),
        new ScheduleCase("no weekly periods", weekly(List.of()), minute -> true));
  }

  private static Resolver weekly(List<Hours> schedule) {
    return resolver(schedule, Map.of());
  }

  private static Resolver resolver(List<Hours> weekly, Map<LocalDate, DayOverride> overrides) {
    return date ->
        BranchService.resolveDay(
            date,
            weekly.isEmpty(),
            weekly.stream()
                .filter(period -> period.dayOfWeek() == date.getDayOfWeek().getValue())
                .toList(),
            overrides.get(date));
  }

  private static long at(LocalDate date, int minute) {
    return date.atStartOfDay(TAIPEI).plusMinutes(minute).toInstant().toEpochMilli();
  }

  private MockHttpSession session(String accountId) {
    var session = new MockHttpSession();
    session.setAttribute("ACCOUNT_ID", accountId);
    session.setAttribute("ACCOUNT_VERSION", identity.sessionVersion(accountId));
    return session;
  }

  private static void assertProblem(
      String message, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(400);
              assertThat(problem).hasMessage(message);
            });
  }

  private static void assertProblemWithoutTomorrow(
      String message, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThat(message).doesNotContain("明日");
    assertProblem(message, call);
  }
}
