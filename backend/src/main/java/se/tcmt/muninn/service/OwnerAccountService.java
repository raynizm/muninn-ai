package se.tcmt.muninn.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.repository.UserRepository;

/**
 * The one account of a Muninn installation: created at first run with the setup token, signs in
 * with email and password, and can reset the password with a recovery token. Nothing here creates a
 * second account.
 */
@Service
@RequiredArgsConstructor
public class OwnerAccountService {

  /** The single message for an unknown email and a wrong password. */
  public static final String BAD_CREDENTIALS = "invalid email or password";

  static final int MIN_PASSWORD_CHARS = 10;

  /** BCrypt only reads the first 72 bytes, so longer passwords are refused instead of truncated. */
  static final int MAX_PASSWORD_BYTES = 72;

  private final UserRepository users;
  private final SetupService setup;
  private final TokenService tokens;
  private final LoginThrottle throttle;
  private final TransactionTemplate tx;

  private final PasswordEncoder encoder = new BCryptPasswordEncoder();

  /** Compared against when the email is unknown, so both failures cost about the same time. */
  private final String dummyHash = encoder.encode("muninn-dummy-password");

  /** A signed-in owner. */
  public record Session(TokenService.TokenPair tokens, AppUser user) {}

  /**
   * First-run: creates the owner, closes setup and signs them in. Needs the setup token, an
   * incomplete setup and no existing account.
   */
  public Session createOwner(String setupToken, String email, String password) {
    if (setup.isComplete()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    String normalized = validEmail(email);
    validatePassword(password);
    if (!setup.verifyToken(setupToken)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid setup token");
    }
    String hash = encoder.encode(password);
    AppUser user =
        tx.execute(
            status -> {
              users.lockForAdminBootstrap();
              if (users.count() > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "an account already exists");
              }
              AppUser created = users.insertOwner(normalized, null, hash);
              setup.markComplete();
              return created;
            });
    return new Session(tokens.issue(user), user);
  }

  /**
   * Signs in with email and password. Attempts are throttled per email only: a per-address limit
   * would lock everyone out behind a reverse proxy, and a single-owner server has only one email
   * worth guessing.
   */
  public Session login(String email, String password) {
    String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    String key = "email:" + normalized;
    if (!throttle.tryAttempt(key)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many attempts");
    }
    AppUser user = users.findByEmail(normalized).orElse(null);
    String hash = user == null ? null : users.passwordHashOf(user.id()).orElse(null);
    boolean ok = encoder.matches(password == null ? "" : password, hash == null ? dummyHash : hash);
    if (!ok || user == null || hash == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
    }
    throttle.reset(key);
    return new Session(tokens.issue(user), user);
  }

  /**
   * Sets a new password using a one-time recovery token (printed by the server on request) and
   * revokes the owner's refresh tokens. A password that is too weak does not use up the token.
   */
  public void resetPassword(String recoveryToken, String newPassword) {
    validatePassword(newPassword);
    AppUser owner =
        users.findOwner().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (!setup.consumeRecoveryToken(recoveryToken)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid recovery token");
    }
    users.setPasswordHash(owner.id(), encoder.encode(newPassword));
    users.deleteRefreshTokens(owner.id());
  }

  /** Trims and lower-cases the email; 400 unless it is one @ with text on both sides. */
  private static String validEmail(String email) {
    String value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    int at = value.indexOf('@');
    if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "email must contain one @ with text on both sides");
    }
    return value;
  }

  /** 400 unless the password is at least 10 characters and at most 72 bytes. */
  private static void validatePassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD_CHARS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "password must be at least " + MIN_PASSWORD_CHARS + " characters");
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "password must be at most " + MAX_PASSWORD_BYTES + " bytes");
    }
  }
}
