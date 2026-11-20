package se.tcmt.muninn.repository;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import se.tcmt.muninn.model.AppUser;

/** app_user access. Emails are stored lower-cased; they are the account identity. */
@Repository
public class UserRepository {

    private static final RowMapper<AppUser> MAPPER = (rs, i) -> new AppUser(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("display_name"),
            AppUser.Role.valueOf(rs.getString("role")));

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findById(UUID id) {
        return jdbc.query("select * from app_user where id = ?", MAPPER, id).stream()
                .findFirst();
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.query("select * from app_user where email = ?", MAPPER, normalize(email)).stream()
                .findFirst();
    }

    public Optional<AppUser> findByOidcIdentity(String issuer, String subject) {
        return jdbc
                .query("select * from app_user where oidc_issuer = ? and oidc_subject = ?", MAPPER, issuer, subject)
                .stream()
                .findFirst();
    }

    /** Inserts unless the email or the (issuer, subject) pair is already taken; returns whether it inserted. */
    public boolean insertIfAbsent(String email, String displayName, AppUser.Role role, String issuer, String subject) {
        return jdbc.update(
                        "insert into app_user(id, email, display_name, role, oidc_issuer, oidc_subject) "
                                + "values (?, ?, ?, ?, ?, ?) on conflict do nothing",
                        UUID.randomUUID(),
                        normalize(email),
                        displayName,
                        role.name(),
                        issuer,
                        subject)
                == 1;
    }

    /** Binds an IdP identity to a legacy row that has none yet; never rebinds an already-bound row. */
    public boolean bindOidcIdentityIfUnbound(UUID id, String issuer, String subject) {
        try {
            return jdbc.update(
                            "update app_user set oidc_issuer = ?, oidc_subject = ? "
                                    + "where id = ? and oidc_subject is null",
                            issuer,
                            subject,
                            id)
                    == 1;
        } catch (DuplicateKeyException e) {
            return false; // a concurrent login already bound this identity to a row
        }
    }

    public AppUser setRole(AppUser user, AppUser.Role role) {
        jdbc.update("update app_user set role = ? where id = ?", role.name(), user.id());
        return new AppUser(user.id(), user.email(), user.displayName(), role);
    }

    public boolean adminExists() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select exists(select 1 from app_user where role = 'ADMIN')", Boolean.class));
    }

    /** Serialises admin bootstrap: blocks concurrent user inserts until the current transaction ends. */
    public void lockForAdminBootstrap() {
        jdbc.execute("lock table app_user in share row exclusive mode");
    }

    private static String normalize(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
