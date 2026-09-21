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
    long now = System.currentTimeMillis();
    db.update(
        "insert into discounts(id,code,name,kind,percent,amount,min_subtotal,branch_id,"
            + "starts_at,ends_at,max_redemptions,redeemed_count,active,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), code, code, "PERCENT", 10, 0, 0, null, null, null, max,
        0, true, now, now);
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
