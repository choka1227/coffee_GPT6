package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffee.branches.api.Branches;
import com.coffee.branches.api.Branches.DayOverride;
import com.coffee.branches.api.Branches.Hours;
import com.coffee.branches.internal.BranchService;
import com.coffee.branches.internal.BranchService.DaySchedule;
import com.coffee.identity.api.Identity;
import com.coffee.orders.api.Orders;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
      "spring.datasource.url=jdbc:h2:mem:branch-hour-overrides;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class BranchHourOverrideTest {
  private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

  @Autowired Branches branches;
  @Autowired Identity identity;
  @Autowired Orders orders;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;

  Actor hq;

  @BeforeEach
  void reset() {
    db.update("delete from branch_day_override_hours");
    db.update("delete from branch_day_overrides");
    db.update("delete from branch_hours");
    db.update("delete from audit_log where action like 'BRANCH_HOURS_OVERRIDE_%'");
    db.update(
        "delete from role_permissions where role_code='MANAGER' and permission='BRANCH_MANAGE'");
    hq = identity.find("hq");
  }

  @Test
  void resolveDayUsesTheFourSpecifiedPriorities() {
    LocalDate saturday = LocalDate.of(2026, 10, 10);
    var weekly = List.of(new Hours(6, 540, 1260));

    assertThat(
            BranchService.resolveDay(
                saturday, false, weekly, new DayOverride(20261010, true, "holiday", List.of())))
        .isEqualTo(new DaySchedule.Closed("holiday"));
    assertThat(
            BranchService.resolveDay(
                saturday,
                false,
                weekly,
                new DayOverride(20261010, false, "", List.of(new Hours(1, 840, 960)), null)))
        .isEqualTo(new DaySchedule.Periods(List.of(new Hours(1, 840, 960)), true));
    assertThat(
            BranchService.resolveDay(
                saturday,
                false,
                weekly,
                new DayOverride(20261010, false, "", List.of(), 40)))
        .isEqualTo(new DaySchedule.Periods(weekly, false));
    assertThat(BranchService.resolveDay(saturday, true, weekly, null))
        .isEqualTo(new DaySchedule.AlwaysOpen());
    assertThat(BranchService.resolveDay(saturday, false, weekly, null))
        .isEqualTo(new DaySchedule.Periods(weekly, false));
    assertThat(BranchService.resolveDay(saturday, false, List.of(), null))
        .isEqualTo(new DaySchedule.Periods(List.of(), false));
  }

  @Test
  void closedOverrideWinsOverAlwaysOpenOnlyOnThatDate() {
    branches.saveOverride(hq, "taipei", new DayOverride(20261010, true, "國定假日", List.of()));

    assertThat(branches.openAt("taipei", taipei(2026, 10, 9, 12, 0))).isTrue();
    assertThat(branches.openAt("taipei", taipei(2026, 10, 10, 12, 0))).isFalse();
    assertThat(branches.openAt("taipei", taipei(2026, 10, 11, 12, 0))).isTrue();
  }

  @Test
  void overridePeriodsReplaceRatherThanAddToWeeklyPeriods() {
    branches.saveHours(hq, "taipei", List.of(new Hours(6, 540, 1260)));
    branches.saveOverride(
        hq, "taipei", new DayOverride(20261010, false, "", List.of(new Hours(3, 840, 960))));

    assertThat(branches.openAt("taipei", taipei(2026, 10, 10, 10, 0))).isFalse();
    assertThat(branches.openAt("taipei", taipei(2026, 10, 10, 15, 0))).isTrue();
  }

  @Test
  void anOverrideBlocksEveryPreviousDayTailButWeeklyTailStillWorks() {
    branches.saveHours(hq, "taipei", List.of(new Hours(5, 1320, 120), new Hours(6, 540, 1260)));
    assertThat(branches.openAt("taipei", taipei(2026, 10, 10, 1, 0))).isTrue();

    branches.saveOverride(hq, "taipei", new DayOverride(20261010, true, "", List.of()));
    assertThat(branches.openAt("taipei", taipei(2026, 10, 10, 1, 0))).isFalse();
    assertThat(branches.openAt("taipei", taipei(2026, 10, 10, 10, 0))).isFalse();

    branches.saveOverride(
        hq, "taipei", new DayOverride(20261010, false, "", List.of(new Hours(6, 540, 720))));
    assertThat(branches.openAt("taipei", taipei(2026, 10, 10, 1, 0))).isFalse();
    assertThat(branches.openAt("taipei", taipei(2026, 10, 10, 10, 0))).isTrue();

    branches.deleteOverride(hq, "taipei", 20261010);
    branches.saveOverride(hq, "taipei", new DayOverride(20261009, true, "", List.of()));
    assertThat(branches.openAt("taipei", taipei(2026, 10, 10, 10, 0))).isTrue();
  }

  @Test
  void validatesOverrideShapeBeforeReplacingStoredData() {
    branches.saveOverride(
        hq, "taipei", new DayOverride(20261010, false, "", List.of(new Hours(6, 540, 720))));

    assertProblem(
        "公休日不能同時設定營業時段",
        () ->
            branches.saveOverride(
                hq, "taipei", new DayOverride(20261010, true, "", List.of(new Hours(6, 60, 120)))));
    assertProblem(
        "請至少設定一個營業時段、改為整天公休，或設定本日最後點餐時間",
        () -> branches.saveOverride(hq, "taipei", new DayOverride(20261010, false, "", List.of())));
    assertProblem(
        "例外日的時段不能跨夜",
        () ->
            branches.saveOverride(
                hq,
                "taipei",
                new DayOverride(20261010, false, "", List.of(new Hours(6, 120, 120)))));
    assertProblem(
        "每天最多 4 個時段",
        () ->
            branches.saveOverride(
                hq,
                "taipei",
                new DayOverride(
                    20261010,
                    false,
                    "",
                    List.of(
                        new Hours(1, 0, 60),
                        new Hours(1, 120, 180),
                        new Hours(1, 240, 300),
                        new Hours(1, 360, 420),
                        new Hours(1, 480, 540)))));
    assertProblem(
        "同一天的營業時段不能重疊",
        () ->
            branches.saveOverride(
                hq,
                "taipei",
                new DayOverride(
                    20261010, false, "", List.of(new Hours(1, 60, 180), new Hours(1, 120, 240)))));
    assertProblem(
        "備註請在 40 字內",
        () ->
            branches.saveOverride(
                hq, "taipei", new DayOverride(20261010, true, "x".repeat(41), List.of())));
    assertProblem(
        "日期格式不正確",
        () -> branches.saveOverride(hq, "taipei", new DayOverride(20261345, true, "", List.of())));

    assertThat(branches.overrides("taipei", 20261010, 20261010)).hasSize(1);
  }

  @Test
  void readsSortedOverridesAndIgnoresIncomingDayOfWeek() {
    branches.saveOverride(hq, "taipei", new DayOverride(20261011, true, null, null));
    branches.saveOverride(
        hq,
        "taipei",
        new DayOverride(
            20261010, false, "short day", List.of(new Hours(3, 900, 960), new Hours(3, 540, 720))));

    assertThat(branches.overrides("taipei", 20261010, 20261011))
        .containsExactly(
            new DayOverride(
                20261010,
                false,
                "short day",
                List.of(new Hours(6, 540, 720), new Hours(6, 900, 960))),
            new DayOverride(20261011, true, "", List.of()));
  }

  @Test
  void dailyLastOrderRoundTripsAndSupportsHoursOrLastOrderOnly() throws Exception {
    branches.saveHours(hq, "taipei", List.of(new Hours(6, 540, 720)), 0);
    String periods =
        "{\"closed\":false,\"note\":\"短日\",\"hours\":[{\"dayOfWeek\":3,"
            + "\"openMinute\":540,\"closeMinute\":720}],\"lastOrderMinutes\":30}";
    mvc.perform(
            put("/api/branches/taipei/hour-overrides/20261010")
                .session(session("hq"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(periods))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastOrderMinutes").value(30));
    mvc.perform(
            get("/api/branches/taipei/hour-overrides")
                .session(session("hq"))
                .param("from", "20261010")
                .param("to", "20261010"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.overrides[0].lastOrderMinutes").value(30));

    String lastOrderOnly =
        "{\"closed\":false,\"note\":\"\",\"hours\":[],\"lastOrderMinutes\":40}";
    mvc.perform(
            put("/api/branches/taipei/hour-overrides/20261010")
                .session(session("hq"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(lastOrderOnly))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hours").isEmpty())
        .andExpect(jsonPath("$.lastOrderMinutes").value(40));

    assertThat(branches.hours("taipei")).containsExactly(new Hours(6, 540, 720));
    assertThat(branches.stateAt("taipei", taipei(2026, 10, 10, 11, 30)))
        .isEqualTo(new Branches.OpenState(true, false, null));
  }

  @Test
  void validatesDailyLastOrderShapeAndRange() {
    assertProblem(
        "公休日不需要設定最後點餐時間",
        () ->
            branches.saveOverride(
                hq, "taipei", new DayOverride(20261010, true, "", List.of(), 30)));
    assertProblem(
        "請至少設定一個營業時段、改為整天公休，或設定本日最後點餐時間",
        () ->
            branches.saveOverride(
                hq, "taipei", new DayOverride(20261010, false, "", List.of(), null)));
    for (int invalid : List.of(-1, 121)) {
      assertProblem(
          "最後點餐提前時間需為 0–120 分鐘",
          () ->
              branches.saveOverride(
                  hq, "taipei", new DayOverride(20261010, false, "", List.of(), invalid)));
    }
  }

  @Test
  void validatesReadRangeAndMissingBranch() {
    assertStatus(404, "找不到分店", () -> branches.overrides("missing", 20261010, 20261011));
    assertProblem("日期範圍不正確", () -> branches.overrides("taipei", 20261011, 20261010));
    assertProblem("日期範圍最多 400 天", () -> branches.overrides("taipei", 20260101, 20270206));
  }

  @Test
  void deletionIsIdempotentAndOnlyAuditsAnActualDeletion() {
    branches.deleteOverride(hq, "taipei", 20261010);
    assertThat(deleteAudits()).isZero();

    branches.saveOverride(
        hq, "taipei", new DayOverride(20261010, false, "", List.of(new Hours(6, 540, 720))));
    branches.deleteOverride(hq, "taipei", 20261010);

    assertThat(deleteAudits()).isEqualTo(1);
    assertThat(db.queryForObject("select count(*) from branch_day_override_hours", Integer.class))
        .isZero();
    assertThat(branches.overrides("taipei", 20261010, 20261010)).isEmpty();
  }

  @Test
  void authenticatedCustomersCanReadButAnonymousUsersCannot() throws Exception {
    branches.saveOverride(hq, "taipei", new DayOverride(20261010, true, "國定假日", List.of()));

    mvc.perform(
            get("/api/branches/taipei/hour-overrides")
                .param("from", "20261010")
                .param("to", "20261010"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/branches/taipei/hour-overrides")
                .session(session("customer"))
                .param("from", "20261010")
                .param("to", "20261010"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.branchId").value("taipei"))
        .andExpect(jsonPath("$.overrides[0].dayOfWeek").value(6))
        .andExpect(jsonPath("$.overrides[0].note").value("國定假日"));
  }

  @Test
  void putUsesPathDateCsrfAndAllowsManagerOnlyForOwnBranch() throws Exception {
    String body =
        "{\"onDate\":20260101,\"closed\":false,\"note\":\"短日\","
            + "\"hours\":[{\"dayOfWeek\":3,\"openMinute\":540,\"closeMinute\":720}]}";

    mvc.perform(
            put("/api/branches/taipei/hour-overrides/20261010")
                .session(session("hq"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/api/branches/taipei/hour-overrides/20261010")
                .session(session("hq"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onDate").value(20261010))
        .andExpect(jsonPath("$.dayOfWeek").value(6))
        .andExpect(jsonPath("$.hours[0].dayOfWeek").value(6));

    assertThat(
            db.queryForObject(
                "select count(*) from audit_log where action='BRANCH_HOURS_OVERRIDE_SAVE'"
                    + " and target_id='taipei:20261010' and branch_id='taipei'",
                Integer.class))
        .isEqualTo(1);

    int today = dateInt(Instant.now().atZone(TAIPEI).toLocalDate());
    mvc.perform(
            put("/api/branches/taipei/hour-overrides/" + today)
                .session(session("manager"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"closed\":true,\"note\":\"\",\"hours\":[]}"))
        .andExpect(status().isOk());
    assertThat(
            db.queryForObject(
                "select count(*) from audit_log where action='BRANCH_HOURS_OVERRIDE_SAVE'"
                    + " and target_id=? and branch_id='taipei'",
                Integer.class,
                "taipei:" + today))
        .isEqualTo(1);

    mvc.perform(
            put("/api/branches/banqiao/hour-overrides/" + today)
                .session(session("manager"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"closed\":true,\"note\":\"\",\"hours\":[]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("只能存取所屬分店資料"));
    mvc.perform(
            put("/api/branches/banqiao/hour-overrides/" + today)
                .session(session("manager2"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"closed\":true,\"note\":\"\",\"hours\":[]}"))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/branches/taipei/hour-overrides/" + today)
                .session(session("manager2"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"closed\":true,\"note\":\"\",\"hours\":[]}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("只能存取所屬分店資料"));

    for (String account : List.of("cashier", "customer")) {
      mvc.perform(
              put("/api/branches/taipei/hour-overrides/" + today)
                  .session(session(account))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"closed\":true,\"note\":\"\",\"hours\":[]}"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.message").value("沒有此功能的操作權限"));
    }
  }

  @Test
  void managerOverrideDatesAreLimitedToTodayThroughFourteenDays() throws Exception {
    LocalDate today = Instant.now().atZone(TAIPEI).toLocalDate();
    String body = "{\"closed\":true,\"note\":\"\",\"hours\":[]}";

    for (LocalDate allowed : List.of(today, today.plusDays(14))) {
      mvc.perform(
              put("/api/branches/taipei/hour-overrides/" + dateInt(allowed))
                  .session(session("manager"))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk());
    }
    for (LocalDate blocked : List.of(today.minusDays(1), today.plusDays(15))) {
      mvc.perform(
              put("/api/branches/taipei/hour-overrides/" + dateInt(blocked))
                  .session(session("manager"))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.message").value("只能設定今天起 14 天內的日期"));
    }
    mvc.perform(
            put("/api/branches/taipei/hour-overrides/" + dateInt(today.plusDays(200)))
                .session(session("hq"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
  }

  @Test
  void managerDeleteUsesTheSameBranchPermissionAndDateRange() throws Exception {
    LocalDate today = Instant.now().atZone(TAIPEI).toLocalDate();
    int todayValue = dateInt(today);
    String body = "{\"closed\":true,\"note\":\"\",\"hours\":[]}";
    mvc.perform(
            put("/api/branches/taipei/hour-overrides/" + todayValue)
                .session(session("manager"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            delete("/api/branches/taipei/hour-overrides/" + todayValue)
                .session(session("manager"))
                .with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(
            delete("/api/branches/banqiao/hour-overrides/" + todayValue)
                .session(session("manager"))
                .with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("只能存取所屬分店資料"));
    mvc.perform(
            delete("/api/branches/taipei/hour-overrides/" + dateInt(today.plusDays(15)))
                .session(session("manager"))
                .with(csrf()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("只能設定今天起 14 天內的日期"));
    mvc.perform(
            delete("/api/branches/taipei/hour-overrides/" + todayValue)
                .session(session("cashier"))
                .with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("沒有此功能的操作權限"));
  }

  @Test
  void deleteEndpointIsIdempotentAndRequiresCsrf() throws Exception {
    branches.saveOverride(hq, "taipei", new DayOverride(20261010, true, "", List.of()));

    mvc.perform(delete("/api/branches/taipei/hour-overrides/20261010").session(session("hq")))
        .andExpect(status().isForbidden());
    mvc.perform(
            delete("/api/branches/taipei/hour-overrides/20261010")
                .session(session("hq"))
                .with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(
            delete("/api/branches/taipei/hour-overrides/20261010")
                .session(session("hq"))
                .with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  void closedOverrideBlocksNewCustomerOrdersButNotExistingOrderCash() throws Exception {
    Actor customer = identity.find("customer");
    Actor cashier = identity.find("cashier");
    Orders.Order existing =
        orders.create(
            customer,
            new Orders.Create(
                "taipei",
                "TAKEAWAY",
                "CASH",
                "例外日測試",
                null,
                List.of(
                    new Orders.LineInput(
                        "latte", 1, List.of("temp-hot", "sugar-none")))),
            "override-existing-" + UUID.randomUUID());
    db.update(
        "update orders set created_at=created_at-86400000 where id=?", existing.id());

    int today = dateInt(LocalDate.now(TAIPEI));
    branches.saveOverride(hq, "taipei", new DayOverride(today, true, "國定假日", List.of()));

    mvc.perform(
            post("/api/orders/" + existing.id() + "/cash")
                .session(session(cashier.id()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tendered\":" + existing.total() + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAID"));

    mvc.perform(
            post("/api/orders")
                .session(session(customer.id()))
                .with(csrf())
                .header("Idempotency-Key", "override-new-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"branchId\":\"taipei\",\"fulfillment\":\"TAKEAWAY\","
                        + "\"paymentMethod\":\"CASH\",\"note\":\"\",\"items\":[{"
                        + "\"productId\":\"latte\",\"quantity\":1,"
                        + "\"optionItemIds\":[\"temp-hot\",\"sugar-none\"]}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("分店今日公休（國定假日）"));
  }

  @Test
  void requireOrderableUsesAllFourSpecifiedMessages() {
    long saturdayMorning = taipei(2026, 10, 10, 8, 0);

    branches.saveOverride(
        hq, "taipei", new DayOverride(20261010, true, "國定假日", List.of()));
    assertProblem(
        "分店今日公休（國定假日）",
        () -> branches.requireOrderable("taipei", saturdayMorning));

    branches.saveOverride(hq, "taipei", new DayOverride(20261010, true, "", List.of()));
    assertProblem("分店今日公休", () -> branches.requireOrderable("taipei", saturdayMorning));

    branches.deleteOverride(hq, "taipei", 20261010);
    branches.saveHours(hq, "taipei", List.of(new Hours(6, 540, 720)));
    assertProblem(
        "分店目前未營業（今日營業時間 09:00–12:00）",
        () -> branches.requireOrderable("taipei", saturdayMorning));

    branches.saveHours(hq, "taipei", List.of(new Hours(7, 540, 720)));
    assertProblem("分店今日未營業", () -> branches.requireOrderable("taipei", saturdayMorning));
  }

  @Test
  void branchListOpenNowUsesOverridesAndWeeklyHoursForThreeBranches() throws Exception {
    var now = java.time.Instant.now().atZone(TAIPEI);
    int today = dateInt(now.toLocalDate());
    int weekday = now.getDayOfWeek().getValue();
    branches.saveOverride(hq, "taipei", new DayOverride(today, true, "", List.of()));
    branches.saveOverride(
        hq,
        "banqiao",
        new DayOverride(today, false, "", List.of(new Hours(weekday, 0, 1440))));
    branches.saveHours(hq, "taichung", List.of(new Hours(weekday, 0, 1440)));

    mvc.perform(get("/api/branches").session(session("customer")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == 'taipei' && @.openNow == false)]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.id == 'banqiao' && @.openNow == true)]").isNotEmpty())
        .andExpect(jsonPath("$[?(@.id == 'taichung' && @.openNow == true)]").isNotEmpty());
  }

  @Test
  void overrideAuditsAreQueryableThroughAuditApi() throws Exception {
    String body = "{\"closed\":true,\"note\":\"稽核測試\",\"hours\":[]}";
    mvc.perform(
            put("/api/branches/taipei/hour-overrides/20261010")
                .session(session("hq"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/audit")
                .session(session("hq"))
                .param("action", "BRANCH_HOURS_OVERRIDE_SAVE")
                .param("branchId", "taipei"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].targetId").value("taipei:20261010"))
        .andExpect(jsonPath("$.items[0].branchId").value("taipei"));

    mvc.perform(
            delete("/api/branches/taipei/hour-overrides/20261010")
                .session(session("hq"))
                .with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(
            get("/api/audit")
                .session(session("hq"))
                .param("action", "BRANCH_HOURS_OVERRIDE_DELETE")
                .param("branchId", "taipei"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].targetId").value("taipei:20261010"))
        .andExpect(jsonPath("$.items[0].branchId").value("taipei"));
  }

  private int deleteAudits() {
    return db.queryForObject(
        "select count(*) from audit_log where action='BRANCH_HOURS_OVERRIDE_DELETE'",
        Integer.class);
  }

  private MockHttpSession session(String accountId) {
    var session = new MockHttpSession();
    session.setAttribute("ACCOUNT_ID", accountId);
    session.setAttribute("ACCOUNT_VERSION", identity.sessionVersion(accountId));
    return session;
  }

  private static long taipei(int year, int month, int day, int hour, int minute) {
    return LocalDateTime.of(year, month, day, hour, minute)
        .atZone(TAIPEI)
        .toInstant()
        .toEpochMilli();
  }

  private static int dateInt(LocalDate date) {
    return Integer.parseInt(date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
  }

  private static void assertProblem(
      String message, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertStatus(400, message, call);
  }

  private static void assertStatus(
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
