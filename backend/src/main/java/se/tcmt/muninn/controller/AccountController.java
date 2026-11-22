package se.tcmt.muninn.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.dto.EmailChangeRequest;
import se.tcmt.muninn.dto.PasswordChangeRequest;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.service.OwnerAccountService;

/** The signed-in owner changing their own login email and password. */
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

  private final OwnerAccountService owner;

  /** Changes the login email; needs the current password. */
  @PutMapping("/email")
  public AuthController.UserView changeEmail(
      @AuthenticationPrincipal AppUser user, @RequestBody EmailChangeRequest req) {
    if (isBlank(req.currentPassword()) || isBlank(req.email())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "current password and email are required");
    }
    AppUser changed = owner.changeEmail(user.id(), req.currentPassword(), req.email());
    return new AuthController.UserView(
        changed.id(), changed.email(), changed.displayName(), changed.role().name());
  }

  /** Changes the password; signs out every other session and returns a fresh token pair. */
  @PutMapping("/password")
  public AuthController.TokenResponse changePassword(
      @AuthenticationPrincipal AppUser user, @RequestBody PasswordChangeRequest req) {
    if (isBlank(req.currentPassword()) || isBlank(req.newPassword())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "current and new password are required");
    }
    OwnerAccountService.Session session =
        owner.changePassword(user.id(), req.currentPassword(), req.newPassword());
    return AuthController.response(session.tokens(), session.user());
  }

  /** Returns true when the string is null or blank. */
  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
