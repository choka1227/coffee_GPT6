package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffee.branches.api.Branches;
import com.coffee.branches.api.Branches.DayOverride;
import com.coffee.branches.api.Branches.Hours;
import com.coffee.branches.internal.BranchService;
import com.coffee.branches.internal.BranchService.DaySchedule;
import com.coffee.identity.api.Identity;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
                new DayOverride(20261010, false, "", List.of(new Hours(1, 840, 960)))))
        .isEqualTo(new DaySchedule.Periods(List.of(new Hours(1, 840, 960)), true));
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
        "請至少設定一個營業時段，或改為整天公休",
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
  void putUsesPathDateCsrfAndHeadquartersScopeAndWritesAudit() throws Exception {
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

    db.update(
        "insert into role_permissions(role_code,permission) values('MANAGER','BRANCH_MANAGE')");
    for (String account : List.of("manager", "cashier", "customer")) {
      mvc.perform(
              put("/api/branches/taipei/hour-overrides/20261011")
                  .session(session(account))
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"closed\":true,\"note\":\"\",\"hours\":[]}"))
          .andExpect(status().isForbidden())
          .andExpect(
              jsonPath("$.message").value(account.equals("manager") ? "此功能限總部範圍" : "沒有此功能的操作權限"));
    }
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
