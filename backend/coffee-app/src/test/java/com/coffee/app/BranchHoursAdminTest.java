package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffee.branches.api.Branches;
import com.coffee.branches.api.Branches.Hours;
import com.coffee.identity.api.Identity;
import com.coffee.shared.Actor;
import com.coffee.shared.Problem;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:branch-hours-admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
class BranchHoursAdminTest {
  @Autowired Branches branches;
  @Autowired Identity identity;
  @Autowired JdbcTemplate db;

  Actor hq;
  Actor customer;

  @BeforeEach
  void reset() {
    db.update("delete from branch_hours");
    db.update("delete from audit_log where action='BRANCH_HOURS_SAVE'");
    hq = identity.find("hq");
    customer = identity.find("customer");
  }

  @Test
  void headquartersCanReplaceReadSortAndClearHoursWithAudit() {
    var saved =
        branches.saveHours(
            hq,
            "taipei",
            List.of(new Hours(2, 600, 1200), new Hours(1, 1020, 1260), new Hours(1, 540, 840)));
    assertThat(saved)
        .containsExactly(
            new Hours(1, 540, 840),
            new Hours(1, 1020, 1260),
            new Hours(2, 600, 1200));
    assertThat(branches.hours("taipei")).containsExactlyElementsOf(saved);
    assertThat(
            db.queryForObject(
                "select count(*) from audit_log where action='BRANCH_HOURS_SAVE' and target_id='taipei'",
                Integer.class))
        .isEqualTo(1);

    assertThat(branches.saveHours(hq, "taipei", List.of())).isEmpty();
    assertThat(branches.openAt("taipei", 0L)).isTrue();
  }

  @Test
  void permissionAndGlobalScopeAreBothRequired() {
    assertProblem(
        403,
        "沒有此功能的操作權限",
        () -> branches.saveHours(customer, "taipei", List.of()));
    Actor branchManager =
        new Actor(
            "manager-hours",
            "manager-hours@example.com",
            "門市主管",
            "MANAGER",
            "BRANCH",
            "taipei",
            Set.of("BRANCH_MANAGE"));
    assertProblem(
        403,
        "此功能限總部範圍",
        () -> branches.saveHours(branchManager, "taipei", List.of()));
  }

  @Test
  void rejectsEveryBoundaryAndPreservesThePreviousBatchAndAudit() {
    var original = List.of(new Hours(1, 540, 840));
    branches.saveHours(hq, "taipei", original);
    int initialAudits = auditCount();

    for (Hours invalid :
        List.of(
            new Hours(0, 60, 120),
            new Hours(8, 60, 120),
            new Hours(1, -1, 120),
            new Hours(1, 1440, 120),
            new Hours(1, 60, 0),
            new Hours(1, 60, 1441))) {
      assertThatThrownBy(() -> branches.saveHours(hq, "taipei", List.of(invalid)))
          .isInstanceOf(Problem.class);
      assertThat(branches.hours("taipei")).containsExactlyElementsOf(original);
    }
    assertThat(auditCount()).isEqualTo(initialAudits);
  }

  @Test
  void rejectsRegularOvernightNextDayAndSundayWrapOverlapButAllowsAdjacency() {
    assertOverlap(new Hours(1, 540, 840), new Hours(1, 800, 1260));
    assertOverlap(new Hours(1, 1320, 120), new Hours(2, 60, 600));
    assertOverlap(new Hours(7, 1320, 120), new Hours(1, 60, 600));

    assertThat(
            branches.saveHours(
                hq, "taipei", List.of(new Hours(1, 540, 840), new Hours(1, 840, 1260))))
        .hasSize(2);
  }

  @Test
  void rejectsMoreThanFourPeriodsInOneDayAndNullBatch() {
    var five =
        List.of(
            new Hours(1, 60, 120),
            new Hours(1, 180, 240),
            new Hours(1, 300, 360),
            new Hours(1, 420, 480),
            new Hours(1, 540, 600));
    assertProblem(400, "每天最多 4 個時段", () -> branches.saveHours(hq, "taipei", five));
    assertProblem(400, "請提供營業時段", () -> branches.saveHours(hq, "taipei", null));
  }

  @Test
  void concurrentReplacementsNeverInterleave() throws Exception {
    var first = List.of(new Hours(1, 540, 840), new Hours(2, 540, 840));
    var second = List.of(new Hours(3, 600, 900), new Hours(4, 600, 900));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a = executor.submit(() -> saveAfterSignal(first, ready, start));
      var b = executor.submit(() -> saveAfterSignal(second, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      a.get(5, TimeUnit.SECONDS);
      b.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertThat(branches.hours("taipei")).isIn(first, second);
  }

  private void saveAfterSignal(
      List<Hours> schedule, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    branches.saveHours(hq, "taipei", schedule);
  }

  private void assertOverlap(Hours first, Hours second) {
    assertProblem(
        400,
        "同一天的營業時段不能重疊",
        () -> branches.saveHours(hq, "taipei", List.of(first, second)));
  }

  private int auditCount() {
    return db.queryForObject(
        "select count(*) from audit_log where action='BRANCH_HOURS_SAVE'", Integer.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
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
