package se.tcmt.muninn.unit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import se.tcmt.muninn.service.LoginThrottle;

class LoginThrottleTest {

  /** Clock the test can move forward. */
  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-11-22T10:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final MovableClock clock = new MovableClock();
  private final LoginThrottle throttle = new LoginThrottle(clock);

  private void attempt(String key, int times) {
    for (int i = 0; i < times; i++) {
      assertThat(throttle.tryAttempt(key)).isTrue();
    }
  }

  @Test
  void blocksTheSixthAttempt() {
    attempt("ann@example.com", LoginThrottle.MAX_FAILURES);

    assertThat(throttle.tryAttempt("ann@example.com")).isFalse();
    assertThat(throttle.tryAttempt("bob@example.com")).isTrue();
  }

  @Test
  void oldAttemptsExpireAfterTheWindow() {
    attempt("ann@example.com", LoginThrottle.MAX_FAILURES);
    assertThat(throttle.tryAttempt("ann@example.com")).isFalse();

    clock.advance(LoginThrottle.WINDOW.plusSeconds(1));

    assertThat(throttle.tryAttempt("ann@example.com")).isTrue();
  }

  @Test
  void keysAreCaseInsensitive() {
    attempt("Ann@Example.com", LoginThrottle.MAX_FAILURES);
    assertThat(throttle.tryAttempt("ann@example.COM")).isFalse();
  }

  @Test
  void tryAttemptIsAtomicUnderParallelCalls() throws Exception {
    int threads = 50;
    var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
    var start = new java.util.concurrent.CountDownLatch(1);
    var allowed = new java.util.concurrent.atomic.AtomicInteger();
    java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                if (throttle.tryAttempt("ann@example.com")) {
                  allowed.incrementAndGet();
                }
                return null;
              }));
    }
    start.countDown();
    for (var f : futures) {
      f.get();
    }
    pool.shutdown();

    assertThat(allowed.get()).isEqualTo(LoginThrottle.MAX_FAILURES);
  }

  @Test
  void aSuccessfulLoginResetsTheCount() {
    for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
      assertThat(throttle.tryAttempt("ann@example.com")).isTrue();
    }
    throttle.reset("ann@example.com");
    for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
      assertThat(throttle.tryAttempt("ann@example.com")).isTrue();
    }
    assertThat(throttle.tryAttempt("ann@example.com")).isFalse();
  }
}
