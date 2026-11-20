package com.muninn.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.muninn.AbstractPostgresIT;
import com.muninn.crypto.SecretCipher;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
class TokenServiceTest extends AbstractPostgresIT {

    @Autowired
    TokenService tokens;

    @Autowired
    AuthSettings settings;

    @Autowired
    UserRepository users;

    @Autowired
    DomainPolicy policy;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SecretCipher cipher;

    AppUser ann;

    @BeforeEach
    void reset() {
        jdbc.update("delete from app_user");
        jdbc.update("delete from app_setting");
        FakeIdp.put(jdbc, "auth.allowedDomains", "acme.com");
        users.insertIfAbsent("ann@acme.com", "Ann", AppUser.Role.USER, "https://idp.test", "sub-ann");
        ann = users.findByOidcIdentity("https://idp.test", "sub-ann").orElseThrow();
    }

    private static void assertUnauthorized(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    private TokenService at(Instant instant) {
        return new TokenService(jdbc, settings, users, policy, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void issuedAccessTokenIdentifiesUser() {
        TokenService.TokenPair pair = tokens.issue(ann);

        AppUser verified = tokens.verifyAccess(pair.access());

        assertThat(verified.id()).isEqualTo(ann.id());
        assertThat(verified.role()).isEqualTo(AppUser.Role.USER);
    }

    @Test
    void refreshRotatesAndOldRefreshFails() throws Exception {
        TokenService.TokenPair first = tokens.issue(ann);
        assertThat(first.refresh()).matches("[A-Za-z0-9_-]{43}");
        List<String> stored = jdbc.queryForList("select token_hash from refresh_token", String.class);
        assertThat(stored).containsExactly(sha256Hex(first.refresh()));

        TokenService.TokenPair second = tokens.refresh(first.refresh());

        assertThat(second.refresh()).isNotEqualTo(first.refresh());
        assertThat(tokens.verifyAccess(second.access()).id()).isEqualTo(ann.id());
        assertUnauthorized(() -> tokens.refresh(first.refresh()));
        assertThat(tokens.refresh(second.refresh()).refresh()).isNotBlank();
    }

    private Instant sessionStartOf(String refresh) throws Exception {
        return jdbc.queryForObject(
                        "select session_started_at from refresh_token where token_hash = ?",
                        java.sql.Timestamp.class,
                        sha256Hex(refresh))
                .toInstant();
    }

    private int refreshRowCount() {
        return jdbc.queryForObject("select count(*) from refresh_token", Integer.class);
    }

    @Test
    void refreshRejectedAndRevokedAfterDomainRemoved() {
        TokenService.TokenPair pair = tokens.issue(ann);
        FakeIdp.put(jdbc, "auth.allowedDomains", "other.com");

        assertUnauthorized(() -> tokens.refresh(pair.refresh()));
        assertThat(refreshRowCount()).isZero();

        // Restoring the domain does not resurrect the revoked token.
        FakeIdp.put(jdbc, "auth.allowedDomains", "ACME.com");
        assertUnauthorized(() -> tokens.refresh(pair.refresh()));
    }

    @Test
    void rotationKeepsSessionStartAndHardCapForcesNewLogin() throws Exception {
        Instant started = Instant.now().minus(Duration.ofDays(29)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        TokenService.TokenPair first = at(started).issue(ann);

        TokenService.TokenPair rotated = tokens.refresh(first.refresh());
        assertThat(sessionStartOf(rotated.refresh())).isEqualTo(started);
        // The rotated token never outlives the session cap.
        Instant expires = jdbc.queryForObject("select expires_at from refresh_token", java.sql.Timestamp.class)
                .toInstant();
        assertThat(expires).isEqualTo(started.plus(Duration.ofDays(30)));

        // Even with a far-future expiry on the row, the session cap is enforced.
        jdbc.update("update refresh_token set expires_at = now() + interval '60 days'");
        assertUnauthorized(() -> at(Instant.now().plus(Duration.ofDays(2))).refresh(rotated.refresh()));
        assertThat(refreshRowCount()).isZero();
    }

    @Test
    void revokeInvalidatesRefreshToken() {
        TokenService.TokenPair pair = tokens.issue(ann);

        tokens.revoke(pair.refresh());

        assertUnauthorized(() -> tokens.refresh(pair.refresh()));
        assertUnauthorized(() -> tokens.refresh("not-a-token"));
    }

    @Test
    void expiredRefreshRejected() {
        TokenService.TokenPair old =
                at(Instant.now().minus(Duration.ofDays(31))).issue(ann);

        assertUnauthorized(() -> tokens.refresh(old.refresh()));
    }

    @Test
    void expiredAccessRejected() {
        TokenService.TokenPair fresh =
                at(Instant.now().minus(Duration.ofMinutes(14))).issue(ann);
        TokenService.TokenPair stale =
                at(Instant.now().minus(Duration.ofMinutes(16))).issue(ann);

        assertThat(tokens.verifyAccess(fresh.access()).id()).isEqualTo(ann.id());
        assertUnauthorized(() -> tokens.verifyAccess(stale.access()));
    }

    @Test
    void forgedOrMalformedAccessRejected() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("muninn")
                .subject(ann.id().toString())
                .claim("role", "ADMIN")
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();

        assertUnauthorized(() -> tokens.verifyAccess(new PlainJWT(claims).serialize()));

        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        SignedJWT wrongKey = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        wrongKey.sign(new MACSigner(otherKey));
        assertUnauthorized(() -> tokens.verifyAccess(wrongKey.serialize()));

        String[] parts = tokens.issue(ann).access().split("\\.");
        String tamperedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        assertUnauthorized(() -> tokens.verifyAccess(parts[0] + "." + tamperedPayload + "." + parts[2]));

        assertUnauthorized(() -> tokens.verifyAccess("garbage"));
        assertUnauthorized(() -> tokens.verifyAccess(null));
    }

    @Test
    void accessRejectedForDeletedUser() {
        TokenService.TokenPair pair = tokens.issue(ann);
        jdbc.update("delete from app_user where id = ?", ann.id());

        assertUnauthorized(() -> tokens.verifyAccess(pair.access()));
    }

    @Test
    void signingKeyStoredEncrypted() {
        tokens.issue(ann);
        String stored =
                jdbc.queryForObject("select value from app_setting where key = 'auth.jwtSigningKey'", String.class);
        String plain = cipher.decrypt(stored);
        assertThat(Base64.getDecoder().decode(plain)).hasSize(32);
        assertThat(stored).doesNotContain(plain);
    }
}
