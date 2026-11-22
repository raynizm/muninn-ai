package se.tcmt.muninn.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.repository.UserRepository;
import se.tcmt.muninn.service.OwnerAccountService;
import se.tcmt.muninn.service.SetupService;
import se.tcmt.muninn.service.TokenService;
import se.tcmt.muninn.support.AbstractPostgresIT;

/** The single owner account: first-run creation, login with throttling, and password reset. */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class OwnerAccountServiceIT extends AbstractPostgresIT {

  private static final Pattern SETUP_TOKEN = Pattern.compile("SETUP TOKEN: (\\S+)");
  private static final Pattern RECOVERY_TOKEN = Pattern.compile("SETUP RECOVERY TOKEN: (\\S+)");
  private static final String PASSWORD = "correct horse battery";

  @Autowired OwnerAccountService owner;

  @Autowired SetupService setup;

  @Autowired UserRepository users;

  @Autowired TokenService tokens;

  @Autowired JdbcTemplate jdbc;

  @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate;

  @BeforeEach
  void reset() {
    jdbc.update("delete from app_user");
    jdbc.update("delete from app_setting");
  }

  private static String lastMatch(Pattern p, CapturedOutput out) {
    Matcher m = p.matcher(out.getAll());
    List<String> all = m.results().map(r -> r.group(1)).toList();
    assertThat(all).isNotEmpty();
    return all.get(all.size() - 1);
  }

  private String freshSetupToken(CapturedOutput out) {
    setup.reissueToken();
    return lastMatch(SETUP_TOKEN, out);
  }

  /** Unique per test so the in-memory throttle never leaks between tests. */
  private static String email() {
    return "owner-" + UUID.randomUUID() + "@example.com";
  }

  private static int statusOf(Throwable t) {
    return ((ResponseStatusException) t).getStatusCode().value();
  }

  @Test
  void createOwnerStoresAHashAndCompletesSetup(CapturedOutput out) {
    String email = email();

    OwnerAccountService.Session session = owner.createOwner(freshSetupToken(out), email, PASSWORD);

    assertThat(session.user().email()).isEqualTo(email);
    assertThat(session.user().role()).isEqualTo(AppUser.Role.ADMIN);
    String hash = jdbc.queryForObject("select password_hash from app_user", String.class);
    assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
    assertThat(setup.isComplete()).isTrue();
    assertThat(tokens.verifyAccess(session.tokens().access()).id()).isEqualTo(session.user().id());
  }

  @Test
  void createOwnerRejectsABadSetupToken(CapturedOutput out) {
    freshSetupToken(out);

    assertThatThrownBy(() -> owner.createOwner("not-the-token", email(), PASSWORD))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
    assertThat(users.count()).isZero();
  }

  @Test
  void createOwnerNeverCreatesASecondAccount(CapturedOutput out) {
    owner.createOwner(freshSetupToken(out), email(), PASSWORD);

    // Setup is complete, so the setup endpoint is closed.
    assertThatThrownBy(() -> owner.createOwner("anything", email(), PASSWORD))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(404));

    // Even if setup state were wiped, an existing account blocks a second one.
    jdbc.update("delete from app_setting");
    String token = freshSetupToken(out);
    assertThatThrownBy(() -> owner.createOwner(token, email(), PASSWORD))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(409));
    assertThat(users.count()).isEqualTo(1);
  }

  static Stream<Arguments> weakInput() {
    return Stream.of(
        Arguments.of("owner@example.com", "short"),
        Arguments.of("owner@example.com", "x".repeat(73)),
        Arguments.of("owner@example.com", "é".repeat(37)), // 74 bytes
        Arguments.of("no-at-sign", PASSWORD),
        Arguments.of("a@", PASSWORD),
        Arguments.of("@example.com", PASSWORD));
  }

  @ParameterizedTest
  @MethodSource("weakInput")
  void createOwnerRejectsWeakInput(String email, String password, CapturedOutput out) {
    String token = freshSetupToken(out);

    assertThatThrownBy(() -> owner.createOwner(token, email, password))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(400));
    assertThat(users.count()).isZero();
    assertThat(setup.isComplete()).isFalse();
  }

  @Test
  void loginReturnsASession(CapturedOutput out) {
    String email = email();
    owner.createOwner(freshSetupToken(out), email, PASSWORD);

    OwnerAccountService.Session session = owner.login(email, PASSWORD);

    assertThat(session.user().email()).isEqualTo(email);
    assertThat(tokens.verifyAccess(session.tokens().access()).email()).isEqualTo(email);
  }

  @Test
  void loginIsCaseInsensitiveOnEmail(CapturedOutput out) {
    String email = email();
    owner.createOwner(freshSetupToken(out), email, PASSWORD);

    assertThat(owner.login(email.toUpperCase(), PASSWORD).user().email()).isEqualTo(email);
  }

  @Test
  void wrongPasswordAndUnknownEmailGiveTheSame401(CapturedOutput out) {
    String email = email();
    owner.createOwner(freshSetupToken(out), email, PASSWORD);

    Throwable wrong =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> owner.login(email, "wrong password!!"));
    Throwable unknown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> owner.login(email(), "wrong password!!"));

    assertThat(statusOf(wrong)).isEqualTo(401);
    assertThat(statusOf(unknown)).isEqualTo(401);
    assertThat(((ResponseStatusException) wrong).getReason())
        .isEqualTo(OwnerAccountService.BAD_CREDENTIALS)
        .isEqualTo(((ResponseStatusException) unknown).getReason());
  }

  @Test
  void fiveFailuresBlockEvenTheCorrectPassword(CapturedOutput out) {
    String email = email();
    owner.createOwner(freshSetupToken(out), email, PASSWORD);

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> owner.login(email, "wrong password!!"))
          .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
    }

    assertThatThrownBy(() -> owner.login(email, PASSWORD))
        .satisfies(
            t ->
                assertThat(((ResponseStatusException) t).getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void failuresForOtherEmailsNeverLockOutTheOwner(CapturedOutput out) {
    String email = email();
    owner.createOwner(freshSetupToken(out), email, PASSWORD);

    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(() -> owner.login(email(), "wrong password!!"))
          .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
    }

    assertThat(owner.login(email, PASSWORD).user().email()).isEqualTo(email);
  }

  @Test
  void resetPasswordSetsANewHashAndRevokesRefreshTokens(CapturedOutput out) {
    String email = email();
    OwnerAccountService.Session first = owner.createOwner(freshSetupToken(out), email, PASSWORD);
    setup.reissueToken(); // setup is complete, so this prints a recovery token
    String recovery = lastMatch(RECOVERY_TOKEN, out);

    owner.resetPassword(recovery, "a brand new password");

    assertThatThrownBy(() -> owner.login(email, PASSWORD))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
    assertThat(owner.login(email, "a brand new password").user().email()).isEqualTo(email);
    assertThatThrownBy(() -> tokens.refresh(first.tokens().refresh()))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
    assertThatThrownBy(() -> owner.resetPassword(recovery, "yet another password"))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401)); // single use
  }

  @Test
  void resetPasswordKeepsTheTokenWhenThePasswordIsTooWeak(CapturedOutput out) {
    owner.createOwner(freshSetupToken(out), email(), PASSWORD);
    setup.reissueToken();
    String recovery = lastMatch(RECOVERY_TOKEN, out);

    assertThatThrownBy(() -> owner.resetPassword(recovery, "short"))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(400));

    owner.resetPassword(recovery, "long enough password"); // the token still works
  }

  @Test
  void changeEmailNeedsTheCurrentPassword(CapturedOutput out) {
    String email = email();
    AppUser me = owner.createOwner(freshSetupToken(out), email, PASSWORD).user();

    assertThatThrownBy(() -> owner.changeEmail(me.id(), "not my password", email()))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(403));

    assertThat(users.findById(me.id()).orElseThrow().email()).isEqualTo(email);
  }

  @Test
  void changeEmailUpdatesTheLoginName(CapturedOutput out) {
    String email = email();
    AppUser me = owner.createOwner(freshSetupToken(out), email, PASSWORD).user();
    String newEmail = email();

    AppUser changed = owner.changeEmail(me.id(), PASSWORD, newEmail.toUpperCase());

    assertThat(changed.email()).isEqualTo(newEmail);
    assertThat(owner.login(newEmail, PASSWORD).user().id()).isEqualTo(me.id());
    assertThatThrownBy(() -> owner.login(email, PASSWORD))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
  }

  @Test
  void changeEmailRejectsABadEmail(CapturedOutput out) {
    AppUser me = owner.createOwner(freshSetupToken(out), email(), PASSWORD).user();

    assertThatThrownBy(() -> owner.changeEmail(me.id(), PASSWORD, "no-at-sign"))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(400));
  }

  @Test
  void changePasswordRevokesOtherSessionsAndKeepsTheCurrentOne(CapturedOutput out) {
    String email = email();
    OwnerAccountService.Session first = owner.createOwner(freshSetupToken(out), email, PASSWORD);

    OwnerAccountService.Session second =
        owner.changePassword(first.user().id(), PASSWORD, "a brand new password");

    assertThatThrownBy(() -> tokens.refresh(first.tokens().refresh()))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
    assertThat(tokens.verifyAccess(second.tokens().access()).id()).isEqualTo(first.user().id());
    assertThat(tokens.refresh(second.tokens().refresh()).access()).isNotBlank();
    assertThatThrownBy(() -> owner.login(email, PASSWORD))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
    assertThat(owner.login(email, "a brand new password").user().email()).isEqualTo(email);
  }

  @Test
  void changePasswordRejectsAWeakPasswordAndKeepsTheOldOne(CapturedOutput out) {
    String email = email();
    AppUser me = owner.createOwner(freshSetupToken(out), email, PASSWORD).user();

    assertThatThrownBy(() -> owner.changePassword(me.id(), PASSWORD, "short"))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(400));

    assertThat(owner.login(email, PASSWORD).user().id()).isEqualTo(me.id());
  }

  @Test
  void wrongCurrentPasswordIsThrottled(CapturedOutput out) {
    AppUser me = owner.createOwner(freshSetupToken(out), email(), PASSWORD).user();

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(
              () -> owner.changePassword(me.id(), "wrong wrong wrong", "another new password"))
          .satisfies(t -> assertThat(statusOf(t)).isEqualTo(403));
    }

    assertThatThrownBy(() -> owner.changePassword(me.id(), PASSWORD, "another new password"))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(429));
  }

  /**
   * Runs a transaction that takes the user's row lock, runs {@code inside}, then waits to commit.
   */
  private java.util.concurrent.Future<?> holdUserLock(
      UUID userId,
      java.util.concurrent.CountDownLatch locked,
      java.util.concurrent.CountDownLatch release,
      Runnable inside,
      java.util.concurrent.ExecutorService pool) {
    return pool.submit(
        () ->
            txTemplate.executeWithoutResult(
                status -> {
                  jdbc.queryForObject(
                      "select 1 from app_user where id = ? for no key update",
                      Integer.class,
                      userId);
                  locked.countDown();
                  try {
                    inside.run();
                    release.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
  }

  @Test
  void aRefreshWaitsForAPasswordChangeInProgress(CapturedOutput out) throws Exception {
    OwnerAccountService.Session session =
        owner.createOwner(freshSetupToken(out), email(), PASSWORD);
    UUID id = session.user().id();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2, daemon());
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    // A password change is in flight and holds the user's lock, but has not touched the tokens yet.
    var change = holdUserLock(id, locked, release, () -> {}, pool);
    try {
      locked.await();
      var refresh = pool.submit(() -> tokens.refresh(session.tokens().refresh()));
      Thread.sleep(400);
      assertThat(refresh.isDone()).as("refresh must wait for the password change").isFalse();

      release.countDown();
      change.get();
      assertThat(refresh.get().access()).isNotBlank(); // nothing was revoked, so it then succeeds
    } finally {
      release.countDown(); // never leave the row locked, whatever happened above
      pool.shutdownNow();
    }
  }

  @Test
  void aPasswordChangeRevokesARefreshThatWasAlreadyInFlight(CapturedOutput out) throws Exception {
    OwnerAccountService.Session session =
        owner.createOwner(freshSetupToken(out), email(), PASSWORD);
    UUID id = session.user().id();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2, daemon());
    var midRefresh = new java.util.concurrent.CountDownLatch(1);
    var finishRefresh = new java.util.concurrent.CountDownLatch(1);
    String inFlightToken = "in-flight-refresh-token-hash";
    // A refresh as production does it: share-lock the user, consume the old token, insert the new
    // one.
    var refresh =
        pool.submit(
            () ->
                txTemplate.executeWithoutResult(
                    status -> {
                      jdbc.queryForObject(
                          "select 1 from app_user where id = ? for share", Integer.class, id);
                      jdbc.update("delete from refresh_token where user_id = ?", id);
                      midRefresh.countDown();
                      try {
                        finishRefresh.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      jdbc.update(
                          "insert into refresh_token(id, user_id, token_hash, expires_at) values (?, ?, ?, now() + interval '1 day')",
                          UUID.randomUUID(),
                          id,
                          inFlightToken);
                    }));
    try {
      midRefresh.await();
      var change = pool.submit(() -> owner.changePassword(id, PASSWORD, "a brand new password"));
      Thread.sleep(400);
      assertThat(change.isDone()).as("the password change must wait for the refresh").isFalse();
      finishRefresh.countDown();
      refresh.get();
      OwnerAccountService.Session renewed = change.get();

      assertThat(
              jdbc.queryForObject(
                  "select count(*) from refresh_token where user_id = ? and token_hash = ?",
                  Integer.class,
                  id,
                  inFlightToken))
          .as("the refresh token that was in flight must not survive")
          .isZero();
      assertThat(tokens.refresh(renewed.tokens().refresh()).access()).isNotBlank();
    } finally {
      finishRefresh.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void aLoginWithTheOldPasswordWaitsForAPasswordChangeAndThenFails(CapturedOutput out)
      throws Exception {
    String email = email();
    OwnerAccountService.Session session = owner.createOwner(freshSetupToken(out), email, PASSWORD);
    UUID id = session.user().id();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2, daemon());
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    String newHash =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
            .encode("a brand new password");
    var change =
        holdUserLock(
            id,
            locked,
            release,
            () -> {
              users.setPasswordHash(id, newHash);
              users.deleteRefreshTokens(id);
            },
            pool);
    try {
      locked.await();
      var login = pool.submit(() -> owner.login(email, PASSWORD));
      Thread.sleep(400);
      assertThat(login.isDone()).as("login must wait for the password change").isFalse();

      release.countDown();
      change.get();
      assertThatThrownBy(login::get).hasCauseInstanceOf(ResponseStatusException.class);
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from refresh_token where user_id = ?", Integer.class, id))
          .isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /** Daemon threads, so a failed assertion cannot keep the test JVM alive on a blocked thread. */
  private static java.util.concurrent.ThreadFactory daemon() {
    return r -> {
      Thread t = new Thread(r);
      t.setDaemon(true);
      return t;
    };
  }
}
