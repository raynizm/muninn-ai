package se.tcmt.muninn.dto;

/**
 * Email and password for creating the owner account or signing in. A password reset sends only the
 * password.
 */
public record AccountRequest(String email, String password) {

  /** Masks the password. */
  @Override
  public String toString() {
    return "AccountRequest[email=" + email + ", password=***]";
  }
}
