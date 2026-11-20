package se.tcmt.muninn.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.crypto.SecretCipher;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.service.OidcLoginService;
import se.tcmt.muninn.service.SetupService;
import se.tcmt.muninn.service.TokenService;
import se.tcmt.muninn.support.AbstractPostgresIT;
import se.tcmt.muninn.support.FakeIdp;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OidcLoginServiceIT extends AbstractPostgresIT {

  private static final FakeIdp IDP = FakeIdp.start();
  private static final String VERIFIER = "pkce-verifier-0123456789";
  private static final String REDIRECT = "http://localhost:5173/auth/callback";
  private static final String TENANT = "6f1c2c3a-0000-4a6e-9b1e-1234567890ab";

  @Autowired OidcLoginService login;

  @Autowired TokenService tokens;

  @Autowired SetupService setup;

  @Autowired SecretCipher cipher;

  @Autowired JdbcTemplate jdbc;

  @AfterAll
  static void stopIdp() {
    IDP.close();
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from app_user");
    jdbc.update("delete from app_setting");
    IDP.seedSettings(jdbc, cipher, "acme.com", null);
  }

  private String code(JWTClaimsSet claims) {
    return IDP.codeFor(IDP.sign(claims));
  }

  private int userCount() {
    return jdbc.queryForObject("select count(*) from app_user", Integer.class);
  }

  private static void assertStatus(HttpStatus expected, ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(expected));
  }

  @Test
  void createsUserOnFirstLogin() {
    setup.markComplete();

    OidcLoginService.LoginResult result =
        login.login(code(IDP.validClaims("Ann@Acme.com").build()), VERIFIER, REDIRECT);

    AppUser user = result.user();
    assertThat(user.email()).isEqualTo("ann@acme.com");
    assertThat(user.displayName()).isEqualTo("Test User");
    assertThat(user.role()).isEqualTo(AppUser.Role.USER);
    assertThat(userCount()).isEqualTo(1);
    assertThat(tokens.verifyAccess(result.tokens().access()).id()).isEqualTo(user.id());
    assertThat(result.tokens().refresh()).isNotBlank();

    assertThat(IDP.lastTokenRequest())
        .containsEntry("grant_type", "authorization_code")
        .containsEntry("code_verifier", VERIFIER)
        .containsEntry("redirect_uri", REDIRECT)
        .containsEntry("client_id", FakeIdp.CLIENT_ID)
        .containsEntry("client_secret", FakeIdp.CLIENT_SECRET);

    AppUser again =
        login.login(code(IDP.validClaims("ann@acme.com").build()), VERIFIER, REDIRECT).user();
    assertThat(again.id()).isEqualTo(user.id());
    assertThat(userCount()).isEqualTo(1);
  }

  @Test
  void existingAdminKeepsRole() {
    setup.markComplete();
    jdbc.update(
        "insert into app_user(id, email, role) values (?, 'boss@acme.com', 'ADMIN')",
        UUID.randomUUID());

    AppUser user =
        login.login(code(IDP.validClaims("boss@acme.com").build()), VERIFIER, REDIRECT).user();

    assertThat(user.role()).isEqualTo(AppUser.Role.ADMIN);
  }

  @Test
  void sameEmailWithDifferentSubjectIsRejected() {
    setup.markComplete();
    AppUser ann =
        login.login(code(IDP.validClaims("ann@acme.com").build()), VERIFIER, REDIRECT).user();

    // Recycled or edited address: same email, different IdP subject.
    assertStatus(
        HttpStatus.FORBIDDEN,
        () ->
            login.login(
                code(IDP.validClaims("Ann@acme.com").subject("someone-else").build()),
                VERIFIER,
                REDIRECT));

    assertThat(userCount()).isEqualTo(1);
    assertThat(
            login
                .login(code(IDP.validClaims("ann@acme.com").build()), VERIFIER, REDIRECT)
                .user()
                .id())
        .isEqualTo(ann.id());
  }

  @Test
  void sameSubjectWithChangedEmailKeepsAccount() {
    setup.markComplete();
    AppUser first =
        login.login(code(IDP.validClaims("ann@acme.com").build()), VERIFIER, REDIRECT).user();

    AppUser again =
        login
            .login(
                code(IDP.validClaims("ann.new@acme.com").subject("sub-ann@acme.com").build()),
                VERIFIER,
                REDIRECT)
            .user();

    assertThat(again.id()).isEqualTo(first.id());
    assertThat(userCount()).isEqualTo(1);
  }

  @Test
  void legacyRowWithoutSubjectIsBoundOnce() {
    setup.markComplete();
    UUID legacyId = UUID.randomUUID();
    jdbc.update(
        "insert into app_user(id, email, role) values (?, 'boss@acme.com', 'ADMIN')", legacyId);

    AppUser user =
        login.login(code(IDP.validClaims("boss@acme.com").build()), VERIFIER, REDIRECT).user();

    assertThat(user.id()).isEqualTo(legacyId);
    assertThat(
            jdbc.queryForMap(
                "select oidc_issuer, oidc_subject from app_user where id = ?", legacyId))
        .containsEntry("oidc_issuer", IDP.issuer())
        .containsEntry("oidc_subject", "sub-boss@acme.com");
    assertStatus(
        HttpStatus.FORBIDDEN,
        () ->
            login.login(
                code(IDP.validClaims("boss@acme.com").subject("other-sub").build()),
                VERIFIER,
                REDIRECT));
  }

  @Test
  void concurrentFirstLoginsCreateOneUser() throws Exception {
    setup.markComplete();
    List<Callable<UUID>> calls = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      String c = code(IDP.validClaims("ann@acme.com").build());
      calls.add(() -> login.login(c, VERIFIER, REDIRECT).user().id());
    }
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      Set<UUID> ids = new HashSet<>();
      for (Future<UUID> f : pool.invokeAll(calls)) {
        ids.add(f.get()); // a 500 (duplicate key) would surface here
      }
      assertThat(ids).hasSize(1);
      assertThat(userCount()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void rejectsBadIssuerSignatureOrAudience() throws Exception {
    setup.markComplete();
    Instant now = Instant.now();

    String otherKeySigned =
        FakeIdp.signWith(FakeIdp.rsaKey(), IDP.validClaims("ann@acme.com").build());
    assertStatus(
        HttpStatus.UNAUTHORIZED,
        () -> login.login(IDP.codeFor(otherKeySigned), VERIFIER, REDIRECT));

    assertStatus(
        HttpStatus.UNAUTHORIZED,
        () ->
            login.login(
                code(IDP.validClaims("ann@acme.com").issuer("https://evil.example").build()),
                VERIFIER,
                REDIRECT));

    assertStatus(
        HttpStatus.UNAUTHORIZED,
        () ->
            login.login(
                code(IDP.validClaims("ann@acme.com").audience("someone-else").build()),
                VERIFIER,
                REDIRECT));

    assertStatus(
        HttpStatus.UNAUTHORIZED,
        () ->
            login.login(
                code(
                    IDP.validClaims("ann@acme.com")
                        .issueTime(Date.from(now.minusSeconds(1200)))
                        .expirationTime(Date.from(now.minusSeconds(600)))
                        .build()),
                VERIFIER,
                REDIRECT));

    String algNone = FakeIdp.unsigned(IDP.validClaims("ann@acme.com").build());
    assertStatus(
        HttpStatus.UNAUTHORIZED, () -> login.login(IDP.codeFor(algNone), VERIFIER, REDIRECT));

    assertStatus(HttpStatus.UNAUTHORIZED, () -> login.login("unknown-code", VERIFIER, REDIRECT));

    // Valid token, but the domain policy says no.
    assertStatus(
        HttpStatus.FORBIDDEN,
        () -> login.login(code(IDP.validClaims("ann@evil.com").build()), VERIFIER, REDIRECT));
    assertStatus(
        HttpStatus.FORBIDDEN,
        () ->
            login.login(
                code(IDP.validClaims("ann@acme.com").claim("email_verified", false).build()),
                VERIFIER,
                REDIRECT));
    assertStatus(
        HttpStatus.FORBIDDEN,
        () ->
            login.login(
                code(IDP.validClaims("ann@acme.com").claim("email_verified", null).build()),
                VERIFIER,
                REDIRECT));

    assertThat(userCount()).isZero();
  }

  @Test
  void pinnedTenantAcceptsMissingEmailVerifiedOnlyForMatchingTid() {
    setup.markComplete();
    FakeIdp.put(jdbc, "auth.tenantId", TENANT);

    assertStatus(
        HttpStatus.FORBIDDEN,
        () ->
            login.login(
                code(
                    IDP.validClaims("ann@acme.com")
                        .claim("email_verified", null)
                        .claim("tid", "11111111-2222-3333-4444-555555555555")
                        .build()),
                VERIFIER,
                REDIRECT));
    assertStatus(
        HttpStatus.FORBIDDEN,
        () ->
            login.login(
                code(
                    IDP.validClaims("ann@acme.com")
                        .claim("email_verified", false)
                        .claim("tid", TENANT)
                        .build()),
                VERIFIER,
                REDIRECT));
    assertThat(userCount()).isZero();

    AppUser user =
        login
            .login(
                code(
                    IDP.validClaims("ann@acme.com")
                        .claim("email_verified", null)
                        .claim("tid", TENANT)
                        .build()),
                VERIFIER,
                REDIRECT)
            .user();
    assertThat(user.email()).isEqualTo("ann@acme.com");
  }

  @Test
  void firstAdminNeedsValidSetupToken(CapturedOutput out) {
    setup.reissueToken();
    Matcher m = Pattern.compile("SETUP TOKEN: (\\S+)").matcher(out.getAll());
    String raw = m.results().reduce((a, b) -> b).orElseThrow().group(1);

    // Setup incomplete: an ordinary login is refused.
    assertStatus(
        HttpStatus.CONFLICT,
        () -> login.login(code(IDP.validClaims("ann@acme.com").build()), VERIFIER, REDIRECT));
    assertStatus(
        HttpStatus.FORBIDDEN,
        () ->
            login.loginAsFirstAdmin(
                code(IDP.validClaims("ann@acme.com").build()), VERIFIER, REDIRECT, raw + "x"));
    assertThat(userCount()).isZero();

    AppUser admin =
        login
            .loginAsFirstAdmin(
                code(IDP.validClaims("ann@acme.com").build()), VERIFIER, REDIRECT, raw)
            .user();
    assertThat(admin.role()).isEqualTo(AppUser.Role.ADMIN);

    // Only one first admin, even with the same still-valid token.
    assertStatus(
        HttpStatus.FORBIDDEN,
        () ->
            login.loginAsFirstAdmin(
                code(IDP.validClaims("bob@acme.com").build()), VERIFIER, REDIRECT, raw));
    assertThat(userCount()).isEqualTo(1);

    setup.markComplete();
    assertStatus(
        HttpStatus.FORBIDDEN,
        () ->
            login.loginAsFirstAdmin(
                code(IDP.validClaims("ann@acme.com").build()), VERIFIER, REDIRECT, raw));
  }
}
