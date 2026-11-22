package se.tcmt.muninn.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: five login attempts per key within fifteen minutes block that key
 * (a successful login clears the count). State is in memory (Muninn runs as a single instance), so
 * a restart clears it.
 */
@Component
public class LoginThrottle {

  public static final int MAX_FAILURES = 5;
  public static final Duration WINDOW = Duration.ofMinutes(15);

  /** Upper bound on tracked keys, so unknown emails cannot grow the map without limit. */
  private static final int MAX_KEYS = 10_000;

  private final Clock clock;
  private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

  /** Creates the throttle on the system clock. */
  @Autowired
  public LoginThrottle() {
    this(Clock.systemUTC());
  }

  /** Creates the throttle on the given clock (for tests). */
  public LoginThrottle(Clock clock) {
    this.clock = clock;
  }

  /**
   * Counts one login attempt for the key and says whether it may go ahead. Check and count are one
   * atomic step, so parallel requests cannot all slip in before the limit is seen. Call {@link
   * #reset} after a successful login.
   */
  public boolean tryAttempt(String key) {
    if (failures.size() >= MAX_KEYS) {
      evictExpired();
    }
    Deque<Instant> recent = failures.computeIfAbsent(normalize(key), k -> new ArrayDeque<>());
    synchronized (recent) {
      prune(recent);
      if (recent.size() >= MAX_FAILURES) {
        return false;
      }
      recent.addLast(clock.instant());
      return true;
    }
  }

  /** Forgets all attempts for the key (after a successful login). */
  public void reset(String key) {
    failures.remove(normalize(key));
  }

  /** Drops failures that fell out of the window. */
  private void prune(Deque<Instant> recent) {
    Instant cutoff = clock.instant().minus(WINDOW);
    while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
      recent.removeFirst();
    }
  }

  /** Removes keys whose failures have all expired; clears everything if that is not enough. */
  private void evictExpired() {
    failures.entrySet().removeIf(e -> expired(e.getValue()));
    if (failures.size() >= MAX_KEYS) {
      failures.clear();
    }
  }

  /** Whether a key has no failure left inside the window. */
  private boolean expired(Deque<Instant> recent) {
    synchronized (recent) {
      prune(recent);
      return recent.isEmpty();
    }
  }

  /** Keys are compared case-insensitively. */
  private static String normalize(String key) {
    return key.toLowerCase(Locale.ROOT);
  }
}
