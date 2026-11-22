package se.tcmt.muninn.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.dto.AccountRequest;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.service.AuthSettings;
import se.tcmt.muninn.service.OidcLoginService;
import se.tcmt.muninn.service.OwnerAccountService;
import se.tcmt.muninn.service.TokenService;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final long ACCESS_TTL_SECONDS = TokenService.ACCESS_TTL.toSeconds();

  private final OidcLoginService login;
  private final OwnerAccountService owner;
  private final TokenService tokens;
  private final AuthSettings settings;

  public record ConfigResponse(String issuer, String clientId, String authorizationEndpoint) {}

  public record CallbackRequest(
      String code, String codeVerifier, String redirectUri, String setupToken) {}

  public record RefreshRequest(String refreshToken) {}

  public record UserView(UUID id, String email, String displayName, String role) {}

  public record TokenResponse(
      String accessToken, String refreshToken, long expiresIn, UserView user) {}

  /**
   * Returns the OIDC settings the client needs to start a login; 404 when SSO is not configured.
   */
  @GetMapping("/config")
  public ConfigResponse config() {
    AuthSettings.Oidc cfg =
        settings
            .oidc()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "oidc not configured"));
    return new ConfigResponse(cfg.issuer(), cfg.clientId(), login.authorizationEndpoint(cfg));
  }

  /**
   * Completes the OIDC code flow and issues a token pair; a setup token makes this the first-admin
   * login.
   */
  @PostMapping("/callback")
  public TokenResponse callback(@RequestBody CallbackRequest req) {
    if (isBlank(req.code()) || isBlank(req.codeVerifier()) || isBlank(req.redirectUri())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "code, codeVerifier and redirectUri are required");
    }
    OidcLoginService.LoginResult result =
        req.setupToken() != null
            ? login.loginAsFirstAdmin(
                req.code(), req.codeVerifier(), req.redirectUri(), req.setupToken())
            : login.login(req.code(), req.codeVerifier(), req.redirectUri());
    return response(result.tokens(), result.user());
  }

  /** Rotates the refresh token and returns a fresh token pair with the signed-in user. */
  @PostMapping("/refresh")
  public TokenResponse refresh(@RequestBody RefreshRequest req) {
    TokenService.TokenPair pair = tokens.refresh(req.refreshToken());
    return response(pair, tokens.verifyAccess(pair.access()));
  }

  /** Signs in with email and password; 401 for any bad credentials, 429 when throttled. */
  @PostMapping("/login")
  public TokenResponse passwordLogin(@RequestBody AccountRequest req) {
    if (isBlank(req.email()) || isBlank(req.password())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email and password are required");
    }
    OwnerAccountService.Session session = owner.login(req.email(), req.password());
    return response(session.tokens(), session.user());
  }

  /** Returns the signed-in user. */
  @GetMapping("/me")
  public UserView me(@AuthenticationPrincipal AppUser user) {
    return new UserView(user.id(), user.email(), user.displayName(), user.role().name());
  }

  /** Revokes the given refresh token. */
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@RequestBody RefreshRequest req) {
    tokens.revoke(req.refreshToken());
  }

  /** Builds the token response for a token pair and its user. */
  static TokenResponse response(TokenService.TokenPair pair, AppUser u) {
    return new TokenResponse(
        pair.access(),
        pair.refresh(),
        ACCESS_TTL_SECONDS,
        new UserView(u.id(), u.email(), u.displayName(), u.role().name()));
  }

  /** Returns true when the string is null or blank. */
  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
