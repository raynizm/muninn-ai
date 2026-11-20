package com.muninn.setup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);
    private static final String TOKEN_HASH_KEY = "setup.token_hash";
    private static final String COMPLETE_KEY = "setup.complete";
    /** Hash of a single-use recovery token, issued only after setup completed (authorizes PUT /api/setup/sso). */
    private static final String RECOVERY_KEY = "setup.recovery";

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public SetupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isComplete() {
        return get(COMPLETE_KEY) != null;
    }

    /** Issues and logs a token if setup is incomplete and none has been issued yet. */
    public void issueTokenIfNeeded() {
        if (isComplete() || get(TOKEN_HASH_KEY) != null) {
            return;
        }
        issueToken();
    }

    /**
     * Replaces any existing token with a new one. Before completion this is a normal setup token; after
     * completion it is a single-use recovery token that only {@link #consumeRecoveryToken} accepts.
     */
    public void reissueToken() {
        if (isComplete()) {
            String raw = newToken();
            put(RECOVERY_KEY, hash(raw));
            log.info("SETUP RECOVERY TOKEN: {} (single use, only for PUT /api/setup/sso)", raw);
        } else {
            issueToken();
        }
    }

    /** True when setup is complete and {@code raw} matches the outstanding recovery token. Does not consume it. */
    public boolean verifyRecoveryToken(String raw) {
        if (raw == null || raw.isEmpty() || !isComplete()) {
            return false;
        }
        String stored = get(RECOVERY_KEY);
        return stored != null
                && MessageDigest.isEqual(
                        stored.getBytes(StandardCharsets.UTF_8), hash(raw).getBytes(StandardCharsets.UTF_8));
    }

    /** Atomically deletes the recovery token if {@code raw} matches it; true only for the single winning call. */
    public boolean consumeRecoveryToken(String raw) {
        if (!verifyRecoveryToken(raw)) {
            return false;
        }
        return jdbc.update("delete from app_setting where key = ? and value = ?", RECOVERY_KEY, hash(raw)) == 1;
    }

    public boolean verifyToken(String raw) {
        if (raw == null || raw.isEmpty() || isComplete()) {
            return false;
        }
        String stored = get(TOKEN_HASH_KEY);
        if (stored == null) {
            return false;
        }
        return MessageDigest.isEqual(
                stored.getBytes(StandardCharsets.UTF_8), hash(raw).getBytes(StandardCharsets.UTF_8));
    }

    public void markComplete() {
        put(COMPLETE_KEY, "true");
        jdbc.update("delete from app_setting where key = ?", TOKEN_HASH_KEY);
    }

    private void issueToken() {
        String raw = newToken();
        put(TOKEN_HASH_KEY, hash(raw));
        log.info("SETUP TOKEN: {}", raw);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String get(String key) {
        return jdbc.query("select value from app_setting where key = ?", rs -> rs.next() ? rs.getString(1) : null, key);
    }

    private void put(String key, String value) {
        jdbc.update(
                "insert into app_setting(key, value) values (?, ?) "
                        + "on conflict (key) do update set value = excluded.value",
                key,
                value);
    }
}
