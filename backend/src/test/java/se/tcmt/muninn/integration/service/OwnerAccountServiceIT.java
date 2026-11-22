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

  private static String client() {
    return "client-" + UUID.randomUUID();
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

    OwnerAccountService.Session session = owner.login(email, PASSWORD, client());

    assertThat(session.user().email()).isEqualTo(email);
    assertThat(tokens.verifyAccess(session.tokens().access()).email()).isEqualTo(email);
  }

  @Test
  void loginIsCaseInsensitiveOnEmail(CapturedOutput out) {
    String email = email();
    owner.createOwner(freshSetupToken(out), email, PASSWORD);

    assertThat(owner.login(email.toUpperCase(), PASSWORD, client()).user().email())
        .isEqualTo(email);
  }

  @Test
  void wrongPasswordAndUnknownEmailGiveTheSame401(CapturedOutput out) {
    String email = email();
    owner.createOwner(freshSetupToken(out), email, PASSWORD);

    Throwable wrong =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> owner.login(email, "wrong password!!", client()));
    Throwable unknown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> owner.login(email(), "wrong password!!", client()));

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
      String client = client(); // different clients: the email itself gets blocked
      assertThatThrownBy(() -> owner.login(email, "wrong password!!", client))
          .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
    }

    assertThatThrownBy(() -> owner.login(email, PASSWORD, client()))
        .satisfies(
            t ->
                assertThat(((ResponseStatusException) t).getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
  }

  @Test
  void resetPasswordSetsANewHashAndRevokesRefreshTokens(CapturedOutput out) {
    String email = email();
    OwnerAccountService.Session first = owner.createOwner(freshSetupToken(out), email, PASSWORD);
    setup.reissueToken(); // setup is complete, so this prints a recovery token
    String recovery = lastMatch(RECOVERY_TOKEN, out);

    owner.resetPassword(recovery, "a brand new password");

    assertThatThrownBy(() -> owner.login(email, PASSWORD, client()))
        .satisfies(t -> assertThat(statusOf(t)).isEqualTo(401));
    assertThat(owner.login(email, "a brand new password", client()).user().email())
        .isEqualTo(email);
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
}
