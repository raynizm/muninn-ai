package se.tcmt.muninn.service;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.repository.UserRepository;

/**
 * Issues our own HS256 access JWTs (15 min) and opaque refresh tokens (30 days). Refresh tokens are
 * stored only as SHA-256 hashes and are single use.
 */
@Service
public class TokenService {

  public static final Duration ACCESS_TTL = Duration.ofMinutes(15);
  static final Duration REFRESH_TTL = Duration.ofDays(30);

  /** Hard cap on a session across rotations; after it a fresh OIDC login is required. */
  static final Duration SESSION_MAX = Duration.ofDays(30);

  private static final String ISSUER = "muninn";

  public record TokenPair(String access, String refresh) {
    /** Masks both tokens. */
    @Override
    public String toString() {
      return "TokenPair[***]";
    }
  }

  private final JdbcTemplate jdbc;
  private final AuthSettings settings;
  private final UserRepository users;
  private final DomainPolicy policy;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  /** Creates the service using the system clock. */
  @Autowired
  public TokenService(
      JdbcTemplate jdbc, AuthSettings settings, UserRepository users, DomainPolicy policy) {
    this(jdbc, settings, users, policy, Clock.systemUTC());
  }

  /** Creates the service with an explicit clock (for tests). */
  public TokenService(
      JdbcTemplate jdbc,
      AuthSettings settings,
      UserRepository users,
      DomainPolicy policy,
      Clock clock) {
    this.jdbc = jdbc;
    this.settings = settings;
    this.users = users;
    this.policy = policy;
    this.clock = clock;
  }

  /** Starts a new session (call after a successful OIDC login). */
  public TokenPair issue(AppUser u) {
    return issue(u, clock.instant());
  }

  /**
   * Issues an access token and a refresh token belonging to the session started at {@code
   * sessionStartedAt}; the refresh token never outlives the session's hard cap.
   */
  private TokenPair issue(AppUser u, Instant sessionStartedAt) {
    Instant now = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(u.id().toString())
            .issuedAt(now)
            .expiresAt(now.plus(ACCESS_TTL))
            .claim("role", u.role().name())
            .build();
    String access =
        new NimbusJwtEncoder(new ImmutableSecret<>(signingKey()))
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();

    byte[] raw = new byte[32];
    random.nextBytes(raw);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Instant expiresAt = min(now.plus(REFRESH_TTL), sessionStartedAt.plus(SESSION_MAX));
    jdbc.update(
        "insert into refresh_token(id, user_id, token_hash, expires_at, session_started_at) "
            + "values (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        u.id(),
        hash(refresh),
        Timestamp.from(expiresAt),
        Timestamp.from(sessionStartedAt));
    return new TokenPair(access, refresh);
  }

  /**
   * Consumes the refresh token (atomic delete) and issues a new pair in the same session. Refused
   * when the token is expired, the session is older than {@link #SESSION_MAX}, or the user's email
   * domain is no longer allowed. The consumed token stays deleted on refusal (no rollback), i.e. it
   * is revoked.
   */
  @Transactional(noRollbackFor = ResponseStatusException.class)
  public TokenPair refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw unauthorized();
    }
    record Row(UUID userId, Instant expiresAt, Instant sessionStartedAt) {}
    // Lock the user before consuming the token, so a password change (which locks the user and then
    // deletes all refresh tokens) cannot slip between this delete and the insert of the new token.
    jdbc
        .query(
            "select user_id from refresh_token where token_hash = ?",
            (rs, i) -> rs.getObject(1, UUID.class),
            hash(refreshToken))
        .stream()
        .findFirst()
        .ifPresent(users::lockShared);
    List<Row> rows =
        jdbc.query(
            "delete from refresh_token where token_hash = ? returning user_id, expires_at, session_started_at",
            (rs, i) ->
                new Row(
                    rs.getObject(1, UUID.class),
                    rs.getTimestamp(2).toInstant(),
                    rs.getTimestamp(3).toInstant()),
            hash(refreshToken));
    Instant now = clock.instant();
    if (rows.isEmpty()) {
      throw unauthorized();
    }
    Row row = rows.get(0);
    if (!row.expiresAt().isAfter(now) || !row.sessionStartedAt().plus(SESSION_MAX).isAfter(now)) {
      throw unauthorized();
    }
    AppUser user = users.findById(row.userId()).orElseThrow(TokenService::unauthorized);
    // Accounts with a password (the owner) are not subject to the SSO allowed-domain list.
    if (!users.hasPassword(user.id()) && !policy.domainAllowed(user.email())) {
      throw unauthorized();
    }
    return issue(user, row.sessionStartedAt());
  }

  /** Deletes a refresh token; unknown or null tokens are ignored. */
  public void revoke(String refreshToken) {
    if (refreshToken != null) {
      jdbc.update("delete from refresh_token where token_hash = ?", hash(refreshToken));
    }
  }

  /** Validates signature, issuer and expiry; returns the user as currently stored. */
  public AppUser verifyAccess(String jwt) {
    if (jwt == null || jwt.isBlank()) {
      throw unauthorized();
    }
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(signingKey()).macAlgorithm(MacAlgorithm.HS256).build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
    timestamps.setClock(clock);
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamps,
            new JwtIssuerValidator(ISSUER),
            new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull)));
    UUID userId;
    try {
      Jwt decoded = decoder.decode(jwt);
      userId = UUID.fromString(Objects.requireNonNullElse(decoded.getSubject(), ""));
    } catch (JwtException | IllegalArgumentException e) {
      throw unauthorized();
    }
    return users.findById(userId).orElseThrow(TokenService::unauthorized);
  }

  /** The HS256 key for access tokens. */
  private SecretKey signingKey() {
    return new SecretKeySpec(settings.jwtSigningKey(), "HmacSHA256");
  }

  /** Returns the earlier of two instants. */
  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  /** Returns the hex SHA-256 of a token. */
  private static String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Builds the generic 401 for any invalid token. */
  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid token");
  }
}
