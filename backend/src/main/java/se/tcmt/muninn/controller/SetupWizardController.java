package se.tcmt.muninn.controller;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.tcmt.muninn.controller.ProviderController.ProviderView;
import se.tcmt.muninn.dto.ProviderRequest;
import se.tcmt.muninn.dto.SsoRequest;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.service.SetupWizardService;

/**
 * Wizard steps. /api/setup/** is permitted at the security layer, so every credential and
 * setup-state check happens in {@link SetupWizardService}; the bearer filter still populates the
 * principal when one is sent.
 */
@RestController
@RequestMapping("/api/setup")
@RequiredArgsConstructor
public class SetupWizardController {

  static final String TOKEN_HEADER = "X-Setup-Token";

  private final SetupWizardService wizard;

  public record ModelsRequest(UUID chatModelId, UUID transcriptionModelId) {}

  /** Saves the SSO configuration using the setup token. */
  @PostMapping("/sso")
  public SetupWizardService.SsoView sso(
      @RequestHeader(name = TOKEN_HEADER, required = false) String token,
      @RequestBody SsoRequest req) {
    return wizard.saveSso(token, req);
  }

  /** Recovery only: needs a recovery token issued after setup completed. */
  @PutMapping("/sso")
  public SetupWizardService.SsoView recoverSso(
      @RequestHeader(name = TOKEN_HEADER, required = false) String token,
      @RequestBody SsoRequest req) {
    return wizard.recoverSso(token, req);
  }

  /** Creates the system AI provider as the first admin. */
  @PostMapping("/provider")
  @ResponseStatus(HttpStatus.CREATED)
  public ProviderView provider(
      @AuthenticationPrincipal AppUser user, @RequestBody(required = false) ProviderRequest req) {
    return ProviderController.view(wizard.createSystemProvider(user, req));
  }

  /** Sets the default chat and transcription models. */
  @PostMapping("/model")
  public ModelsRequest models(
      @AuthenticationPrincipal AppUser user, @RequestBody(required = false) ModelsRequest req) {
    ModelsRequest r = req == null ? new ModelsRequest(null, null) : req;
    wizard.setDefaultModels(user, r.chatModelId(), r.transcriptionModelId());
    return r;
  }

  /** Marks setup as complete. */
  @PostMapping("/finish")
  public Map<String, Boolean> finish(@AuthenticationPrincipal AppUser user) {
    wizard.finish(user);
    return Map.of("complete", true);
  }
}
