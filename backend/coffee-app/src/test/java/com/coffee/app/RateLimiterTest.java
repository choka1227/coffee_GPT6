package com.coffee.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffee.shared.Problem;
import com.coffee.shared.RateLimiter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
  @Test
  void allowsTheLimitThenReturnsTheConfiguredProblem() {
    var limiter = new RateLimiter("訊息", 3, 1_000, 10);

    limiter.hit("k", 0);
    limiter.hit("k", 0);
    limiter.hit("k", 0);

    assertThatThrownBy(() -> limiter.hit("k", 0))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(429);
              assertThat(problem).hasMessage("訊息");
            });
  }

  @Test
  void windowStartsAtTheFirstHitAndTheBoundaryStartsANewWindow() {
    var limiter = new RateLimiter("訊息", 3, 1_000, 10);

    limiter.hit("k", 0);
    limiter.hit("k", 500);
    limiter.hit("k", 999);
    limiter.hit("k", 1_000);
    limiter.hit("k", 1_001);
    limiter.hit("k", 1_002);

    assertThatThrownBy(() -> limiter.hit("k", 1_003)).isInstanceOf(Problem.class);
  }

  @Test
  void rejectedHitsDoNotIncreaseTheCountOrExtendTheWindow() {
    var limiter = new RateLimiter("訊息", 2, 1_000, 10);
    limiter.hit("k", 0);
    limiter.hit("k", 1);

    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(() -> limiter.hit("k", 999)).isInstanceOf(Problem.class);
    }

    assertThatCode(() -> limiter.hit("k", 1_000)).doesNotThrowAnyException();
    assertThatCode(() -> limiter.hit("k", 1_001)).doesNotThrowAnyException();
    assertThatThrownBy(() -> limiter.hit("k", 1_002)).isInstanceOf(Problem.class);
  }

  @Test
  void keysAreIndependentAndClearOnlyResetsTheSelectedKey() {
    var limiter = new RateLimiter("訊息", 1, 1_000, 10);
    limiter.hit("a", 0);
    limiter.hit("b", 0);

    assertThatThrownBy(() -> limiter.hit("a", 0)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> limiter.hit("b", 0)).isInstanceOf(Problem.class);

    limiter.clear("a");

    assertThatCode(() -> limiter.hit("a", 0)).doesNotThrowAnyException();
    assertThatThrownBy(() -> limiter.hit("b", 0)).isInstanceOf(Problem.class);
  }

  @Test
  void maxKeysRejectsWhenNothingExpiredAndRecoversAfterCleanup() {
    var limiter = new RateLimiter("x", 1, 1_000, 2);
    limiter.hit("a", 0);
    limiter.hit("b", 0);
    limiter.hit("c", 0);

    assertThat(limiter.size()).isEqualTo(3);
    assertThatThrownBy(() -> limiter.hit("d", 0))
        .isInstanceOfSatisfying(
            Problem.class,
            problem -> {
              assertThat(problem.status).isEqualTo(429);
              assertThat(problem).hasMessage("系統忙碌，請稍後再試");
            });

    assertThatCode(() -> limiter.hit("d", 1_000)).doesNotThrowAnyException();
    assertThat(limiter.size()).isEqualTo(1);
  }

  @Test
  void concurrentHitsForTheSameKeyAllowExactlyOne() throws Exception {
    int workers = 8;
    var limiter = new RateLimiter("訊息", 1, 1_000, 10);
    var ready = new CountDownLatch(workers);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(workers);
    try {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int i = 0; i < workers; i++) {
        futures.add(executor.submit(() -> hitAfterSignal(limiter, ready, start)));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      int successes = 0;
      for (var future : futures) successes += outcome(future);
      assertThat(successes).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void constructorRejectsNonPositiveNumericParameters() {
    assertThatThrownBy(() -> new RateLimiter("訊息", 0, 1, 1)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> new RateLimiter("訊息", 1, 0, 1)).isInstanceOf(Problem.class);
    assertThatThrownBy(() -> new RateLimiter("訊息", 1, 1, 0)).isInstanceOf(Problem.class);
  }

  private boolean hitAfterSignal(
      RateLimiter limiter, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    limiter.hit("same", 0);
    return true;
  }

  private int outcome(java.util.concurrent.Future<Boolean> future) throws Exception {
    try {
      return future.get(5, TimeUnit.SECONDS) ? 1 : 0;
    } catch (ExecutionException failed) {
      assertThat(failed.getCause())
          .isInstanceOfSatisfying(
              Problem.class, problem -> assertThat(problem.status).isEqualTo(429));
      return 0;
    }
  }

  private void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
