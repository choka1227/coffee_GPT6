package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffee.catalog.api.Discounts;
import com.coffee.shared.Problem;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
      "spring.datasource.url=jdbc:h2:mem:discount-redemption;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
    })
@ActiveProfiles("dev")
class DiscountRedemptionTest {
  @Autowired Discounts discounts;
  @Autowired JdbcTemplate db;

  @BeforeEach
  void reset() {
    db.update("delete from order_discounts");
    db.update("delete from discounts");
  }

  @Test
  void nullAndBlankCodesDoNothing() {
    assertThat(discounts.apply(null, "taipei", 100, 1L)).isNull();
    assertThat(discounts.apply("  ", "taipei", 100, 1L)).isNull();
    assertThat(db.queryForObject("select count(*) from discounts", Integer.class)).isZero();
  }

  @Test
  void limitedCodeStopsAtTheCapAndUnlimitedCodeAlwaysCounts() {
    insert("LIMIT-ONE", 1);
    discounts.apply(" limit-one ", "taipei", 505, 1L);
    assertThatThrownBy(() -> discounts.apply("LIMIT-ONE", "taipei", 505, 1L))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(409);
              assertThat(problem).hasMessage("此優惠碼的使用次數已達上限");
            });
    assertThat(count("LIMIT-ONE")).isEqualTo(1);

    insert("NO-LIMIT", null);
    discounts.apply("NO-LIMIT", "taipei", 505, 1L);
    discounts.apply("NO-LIMIT", "taipei", 505, 1L);
    assertThat(count("NO-LIMIT")).isEqualTo(2);
  }

  @Test
  void unavailableCodesReturnTheSameNotFoundProblem() {
    long at = 1_000_000L;
    insert("INACTIVE", null, false, null, null, null);
    insert("FUTURE", null, true, at + 1, null, null);
    insert("EXPIRED", null, true, null, at - 1, null);
    insert("OTHER-BRANCH", null, true, null, null, "taichung");

    assertInvalid("INACTIVE", at);
    assertInvalid("FUTURE", at);
    assertInvalid("EXPIRED", at);
    assertInvalid("OTHER-BRANCH", at);
    assertInvalid("MISSING", at);

    assertThat(count("INACTIVE")).isZero();
    assertThat(count("FUTURE")).isZero();
    assertThat(count("EXPIRED")).isZero();
    assertThat(count("OTHER-BRANCH")).isZero();
  }

  @Test
  void startAndEndTimesAreInclusive() {
    long at = 1_000_000L;
    insert("START-NOW", null, true, at, null, null);
    insert("END-NOW", null, true, null, at, null);

    assertThat(discounts.apply("START-NOW", "taipei", 505, at)).isNotNull();
    assertThat(discounts.apply("END-NOW", "taipei", 505, at)).isNotNull();
    assertThat(count("START-NOW")).isEqualTo(1);
    assertThat(count("END-NOW")).isEqualTo(1);
  }

  @Test
  void concurrentRedemptionsCannotExceedTheCap() throws Exception {
    insert("RACE-ONE", 1);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> applyAfterSignal("RACE-ONE", ready, start));
      var second = executor.submit(() -> applyAfterSignal("RACE-ONE", ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int successes = outcome(first) + outcome(second);
      assertThat(successes).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("RACE-ONE")).isEqualTo(1);
  }

  @Test
  void quoteTwentyTimesDoesNotConsumeTheCode() {
    insert("QUOTE-20", 1);

    for (int i = 0; i < 20; i++) {
      assertThat(discounts.quote("QUOTE-20", "taipei", 505, 1L)).isNotNull();
    }

    assertThat(count("QUOTE-20")).isZero();
  }

  @Test
  void quoteThenApplyConsumesTheCodeOnce() {
    insert("QUOTE-APPLY", 1);

    assertThat(discounts.quote("QUOTE-APPLY", "taipei", 505, 1L)).isNotNull();
    assertThat(count("QUOTE-APPLY")).isZero();
    assertThat(discounts.apply("QUOTE-APPLY", "taipei", 505, 1L)).isNotNull();

    assertThat(count("QUOTE-APPLY")).isEqualTo(1);
  }

  @Test
  void quoteAndApplyReturnTheSameAppliedFields() {
    insert("SAME-RESULT", 2);

    var quoted = discounts.quote(" same-result ", "taipei", 505, 1L);
    var applied = discounts.apply("SAME-RESULT", "taipei", 505, 1L);

    assertThat(quoted).isEqualTo(applied);
    assertThat(count("SAME-RESULT")).isEqualTo(1);
  }

  @Test
  void quoteAndApplyRejectTheSameInvalidRules() {
    long at = 1_000_000L;
    insert("QUOTE-INACTIVE", null, false, null, null, null);
    insert("QUOTE-FUTURE", null, true, at + 1, null, null);
    insert("QUOTE-EXPIRED", null, true, null, at - 1, null);
    insert("QUOTE-BRANCH", null, true, null, null, "taichung");
    insertWithMinimum("QUOTE-MINIMUM", 506);
    insert("QUOTE-CAPPED", 1);
    db.update("update discounts set redeemed_count=1 where code=?", "QUOTE-CAPPED");

    assertSameProblem("QUOTE-INACTIVE", 505, at);
    assertSameProblem("QUOTE-FUTURE", 505, at);
    assertSameProblem("QUOTE-EXPIRED", 505, at);
    assertSameProblem("QUOTE-BRANCH", 505, at);
    assertSameProblem("QUOTE-MINIMUM", 505, at);
    assertSameProblem("QUOTE-CAPPED", 505, at);
  }

  private boolean applyAfterSignal(String code, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    discounts.apply(code, "taipei", 505, 1L);
    return true;
  }

  private int outcome(java.util.concurrent.Future<Boolean> future) throws Exception {
    try {
      return future.get(5, TimeUnit.SECONDS) ? 1 : 0;
    } catch (ExecutionException failed) {
      assertThat(failed.getCause())
          .isInstanceOfSatisfying(
              Problem.class, problem -> assertThat(problem.status).isEqualTo(409));
      return 0;
    }
  }

  private void insert(String code, Integer max) {
    insert(code, max, true, null, null, null);
  }

  private void insert(
      String code, Integer max, boolean active, Long startsAt, Long endsAt, String branchId) {
    long now = System.currentTimeMillis();
    db.update(
        "insert into discounts(id,code,name,kind,percent,amount,min_subtotal,branch_id,"
            + "starts_at,ends_at,max_redemptions,redeemed_count,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), code, code, "PERCENT", 10, 0, 0, branchId, startsAt,
        endsAt, max, 0, active, now, now);
  }

  private void insertWithMinimum(String code, int minSubtotal) {
    long now = System.currentTimeMillis();
    db.update(
        "insert into discounts(id,code,name,kind,percent,amount,min_subtotal,branch_id,"
            + "starts_at,ends_at,max_redemptions,redeemed_count,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), code, code, "PERCENT", 10, 0, minSubtotal, null, null,
        null, null, 0, true, now, now);
  }

  private void assertSameProblem(String code, int subtotal, long at) {
    Problem quoted = problem(() -> discounts.quote(code, "taipei", subtotal, at));
    Problem applied = problem(() -> discounts.apply(code, "taipei", subtotal, at));

    assertThat(quoted.status).isEqualTo(applied.status);
    assertThat(quoted).hasMessage(applied.getMessage());
  }

  private Problem problem(Runnable action) {
    try {
      action.run();
      throw new AssertionError("預期優惠碼驗證失敗");
    } catch (Problem problem) {
      return problem;
    }
  }

  private void assertInvalid(String code, long at) {
    assertThatThrownBy(() -> discounts.apply(code, "taipei", 505, at))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(404);
              assertThat(problem).hasMessage("優惠碼不存在或已失效");
            });
  }

  private int count(String code) {
    return db.queryForObject(
        "select redeemed_count from discounts where code=?", Integer.class, code);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
