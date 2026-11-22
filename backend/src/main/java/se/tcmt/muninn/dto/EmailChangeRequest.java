package se.tcmt.muninn.dto;

/** New login email, confirmed with the current password. */
public record EmailChangeRequest(String currentPassword, String email) {

  /** Masks the password. */
  @Override
  public String toString() {
    return "EmailChangeRequest[currentPassword=***, email=" + email + "]";
  }
}
