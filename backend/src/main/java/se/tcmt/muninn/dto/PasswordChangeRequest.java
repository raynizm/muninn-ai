package se.tcmt.muninn.dto;

/** New password, confirmed with the current one. */
public record PasswordChangeRequest(String currentPassword, String newPassword) {

  /** Masks both passwords. */
  @Override
  public String toString() {
    return "PasswordChangeRequest[currentPassword=***, newPassword=***]";
  }
}
