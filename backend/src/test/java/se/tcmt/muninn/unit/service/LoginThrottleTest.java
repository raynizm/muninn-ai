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

  private void fail(String key, int times) {
    for (int i = 0; i < times; i++) {
      throttle.recordFailure(key);
    }
  }

  @Test
  void blocksAfterFiveFailures() {
    fail("ann@example.com", LoginThrottle.MAX_FAILURES - 1);
    assertThat(throttle.isBlocked("ann@example.com")).isFalse();

    throttle.recordFailure("ann@example.com");

    assertThat(throttle.isBlocked("ann@example.com")).isTrue();
    assertThat(throttle.isBlocked("bob@example.com")).isFalse();
  }

  @Test
  void oldFailuresExpireAfterTheWindow() {
    fail("ann@example.com", LoginThrottle.MAX_FAILURES);
    assertThat(throttle.isBlocked("ann@example.com")).isTrue();

    clock.advance(LoginThrottle.WINDOW.plusSeconds(1));

    assertThat(throttle.isBlocked("ann@example.com")).isFalse();
  }

  @Test
  void resetClearsFailures() {
    fail("ann@example.com", LoginThrottle.MAX_FAILURES);

    throttle.reset("ann@example.com");

    assertThat(throttle.isBlocked("ann@example.com")).isFalse();
  }

  @Test
  void keysAreCaseInsensitive() {
    fail("Ann@Example.com", LoginThrottle.MAX_FAILURES);
    assertThat(throttle.isBlocked("ann@example.COM")).isTrue();
  }
}
