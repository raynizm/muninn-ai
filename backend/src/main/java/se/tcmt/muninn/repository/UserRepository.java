package se.tcmt.muninn.repository;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import se.tcmt.muninn.model.AppUser;

/** app_user access. Emails are stored lower-cased; they are the account identity. */
@Repository
@RequiredArgsConstructor
public class UserRepository {

  private static final RowMapper<AppUser> MAPPER =
      (rs, i) ->
          new AppUser(
              rs.getObject("id", UUID.class),
              rs.getString("email"),
              rs.getString("display_name"),
              AppUser.Role.valueOf(rs.getString("role")));

  private final JdbcTemplate jdbc;

  /** Finds a user by id. */
  public Optional<AppUser> findById(UUID id) {
    return jdbc.query("select * from app_user where id = ?", MAPPER, id).stream().findFirst();
  }

  /** Finds a user by email, case-insensitively. */
  public Optional<AppUser> findByEmail(String email) {
    return jdbc.query("select * from app_user where email = ?", MAPPER, normalize(email)).stream()
        .findFirst();
  }

  /** Finds the user bound to an IdP (issuer, subject) pair. */
  public Optional<AppUser> findByOidcIdentity(String issuer, String subject) {
    return jdbc
        .query(
            "select * from app_user where oidc_issuer = ? and oidc_subject = ?",
            MAPPER,
            issuer,
            subject)
        .stream()
        .findFirst();
  }

  /**
   * Inserts unless the email or the (issuer, subject) pair is already taken; returns whether it
   * inserted.
   */
  public boolean insertIfAbsent(
      String email, String displayName, AppUser.Role role, String issuer, String subject) {
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

  /**
   * Binds an IdP identity to a legacy row that has none yet; never rebinds an already-bound row.
   */
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

  /** Changes the user's role and returns the updated user. */
  public AppUser setRole(AppUser user, AppUser.Role role) {
    jdbc.update("update app_user set role = ? where id = ?", role.name(), user.id());
    return new AppUser(user.id(), user.email(), user.displayName(), role);
  }

  /** Whether at least one ADMIN exists. */
  public boolean adminExists() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from app_user where role = 'ADMIN')", Boolean.class));
  }

  /**
   * Serialises admin bootstrap: blocks concurrent user inserts until the current transaction ends.
   */
  public void lockForAdminBootstrap() {
    jdbc.execute("lock table app_user in share row exclusive mode");
  }

  /** The stored BCrypt hash of the user's password, if the account has one. */
  public Optional<String> passwordHashOf(UUID userId) {
    return jdbc
        .query(
            "select password_hash from app_user where id = ? and password_hash is not null",
            (rs, i) -> rs.getString(1),
            userId)
        .stream()
        .findFirst();
  }

  /** Whether any account signs in with a password, i.e. this server has a single owner. */
  public boolean hasPasswordAccount() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from app_user where password_hash is not null)",
            Boolean.class));
  }

  /** Whether the account signs in with a password. */
  public boolean hasPassword(UUID userId) {
    return passwordHashOf(userId).isPresent();
  }

  /** Creates the owner: role ADMIN, signs in with a password, no SSO identity. */
  public AppUser insertOwner(String email, String displayName, String passwordHash) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into app_user(id, email, display_name, role, password_hash) values (?, ?, ?, 'ADMIN', ?)",
        id,
        normalize(email),
        displayName,
        passwordHash);
    return findById(id).orElseThrow();
  }

  /** Replaces the user's password hash. */
  public void setPasswordHash(UUID userId, String passwordHash) {
    jdbc.update("update app_user set password_hash = ? where id = ?", passwordHash, userId);
  }

  /**
   * Changes the login email (stored lower-cased); a duplicate raises {@code DuplicateKeyException}.
   */
  public void updateEmail(UUID userId, String email) {
    jdbc.update("update app_user set email = ? where id = ?", normalize(email), userId);
  }

  /** Number of accounts. */
  public long count() {
    Long n = jdbc.queryForObject("select count(*) from app_user", Long.class);
    return n == null ? 0 : n;
  }

  /** The first (and normally only) account. */
  public Optional<AppUser> findOwner() {
    return jdbc.query("select * from app_user order by created_at, id limit 1", MAPPER).stream()
        .findFirst();
  }

  /** Revokes every refresh token of the user; returns how many were deleted. */
  public int deleteRefreshTokens(UUID userId) {
    return jdbc.update("delete from refresh_token where user_id = ?", userId);
  }

  /** Lower-cases the email for storage and lookup. */
  private static String normalize(String email) {
    return email.toLowerCase(Locale.ROOT);
  }
}
