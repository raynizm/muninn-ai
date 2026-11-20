package com.muninn.auth;

import com.muninn.crypto.SecretCipher;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Reads auth configuration from app_setting. Secrets are stored encrypted with {@link SecretCipher}. */
@Component
public class AuthSettings {

    static final String ISSUER = "oidc.issuer";
    static final String CLIENT_ID = "oidc.clientId";
    static final String CLIENT_SECRET = "oidc.clientSecret";
    static final String ALLOWED_DOMAINS = "auth.allowedDomains";
    static final String TENANT_ID = "auth.tenantId";
    static final String JWT_SIGNING_KEY = "auth.jwtSigningKey";

    private static final int SIGNING_KEY_BYTES = 32;
    private static final Pattern DOMAIN =
            Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public AuthSettings(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public record Oidc(String issuer, String clientId, String clientSecret) {
        @Override
        public String toString() {
            return "Oidc[issuer=" + issuer + ", clientId=" + clientId + ", clientSecret=***]";
        }
    }

    /** Present only when issuer, client id and client secret are all configured. */
    public Optional<Oidc> oidc() {
        String issuer = get(ISSUER);
        String clientId = get(CLIENT_ID);
        String secret = get(CLIENT_SECRET);
        if (isBlank(issuer) || isBlank(clientId) || isBlank(secret)) {
            return Optional.empty();
        }
        return Optional.of(new Oidc(issuer.trim(), clientId.trim(), cipher.decrypt(secret)));
    }

    /** Lower-cased domains; empty means nobody may sign in. */
    public Set<String> allowedDomains() {
        String raw = get(ALLOWED_DOMAINS);
        if (raw == null) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .map(d -> d.startsWith("@") ? d.substring(1) : d)
                .filter(d -> !d.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Configured issuer without touching the secret (for admin views). */
    public Optional<String> issuer() {
        return Optional.ofNullable(get(ISSUER)).filter(v -> !v.isBlank()).map(String::trim);
    }

    /** Configured client id without touching the secret (for admin views). */
    public Optional<String> clientId() {
        return Optional.ofNullable(get(CLIENT_ID)).filter(v -> !v.isBlank()).map(String::trim);
    }

    /** Whether issuer, client id and an (encrypted) client secret are all stored; never decrypts. */
    public boolean oidcConfigured() {
        return issuer().isPresent() && clientId().isPresent() && !isBlank(get(CLIENT_SECRET));
    }

    /** Stores the OIDC client; the secret is encrypted with {@link SecretCipher} and never read back out here. */
    public void saveOidc(String issuer, String clientId, String clientSecret) {
        put(ISSUER, issuer);
        put(CLIENT_ID, clientId);
        put(CLIENT_SECRET, cipher.encrypt(clientSecret));
    }

    /** Stores an already-normalised, non-empty list (see {@link #normalizeDomains}). */
    public void saveAllowedDomains(Collection<String> domains) {
        put(ALLOWED_DOMAINS, String.join(",", domains));
    }

    /** Blank or null removes the tenant pin. */
    public void saveTenantId(String tenantId) {
        if (isBlank(tenantId)) {
            jdbc.update("delete from app_setting where key = ?", TENANT_ID);
        } else {
            put(TENANT_ID, tenantId.trim());
        }
    }

    /**
     * Trims, lower-cases, strips a leading '@', drops blanks and duplicates, sorts. 400 when the result is
     * empty (the allow list is mandatory) or an entry is not a plain domain name.
     */
    public static List<String> normalizeDomains(Collection<String> raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "allowedDomains must not be empty");
        }
        List<String> out = raw.stream()
                .filter(d -> d != null)
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .map(d -> d.startsWith("@") ? d.substring(1) : d)
                .filter(d -> !d.isEmpty())
                .distinct()
                .sorted()
                .toList();
        if (out.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "allowedDomains must not be empty");
        }
        for (String d : out) {
            if (d.length() > 253 || !DOMAIN.matcher(d).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid domain in allowedDomains");
            }
        }
        return out;
    }

    public Optional<String> tenantId() {
        String raw = get(TENANT_ID);
        return isBlank(raw) ? Optional.empty() : Optional.of(raw.trim());
    }

    /** HS256 key for our own access tokens; generated and stored encrypted on first use. */
    public byte[] jwtSigningKey() {
        String stored = get(JWT_SIGNING_KEY);
        if (stored == null) {
            byte[] key = new byte[SIGNING_KEY_BYTES];
            random.nextBytes(key);
            jdbc.update(
                    "insert into app_setting(key, value) values (?, ?) on conflict (key) do nothing",
                    JWT_SIGNING_KEY,
                    cipher.encrypt(Base64.getEncoder().encodeToString(key)));
            stored = get(JWT_SIGNING_KEY); // re-read: a concurrent caller may have won the insert
        }
        return Base64.getDecoder().decode(cipher.decrypt(stored));
    }

    private void put(String key, String value) {
        jdbc.update(
                "insert into app_setting(key, value) values (?, ?) "
                        + "on conflict (key) do update set value = excluded.value",
                key,
                value);
    }

    private String get(String key) {
        return jdbc.query("select value from app_setting where key = ?", rs -> rs.next() ? rs.getString(1) : null, key);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
