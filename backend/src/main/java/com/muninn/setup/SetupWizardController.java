package com.muninn.setup;

import com.muninn.auth.AppUser;
import com.muninn.provider.ProviderController;
import com.muninn.provider.ProviderController.ProviderView;
import com.muninn.provider.ProviderRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wizard steps. /api/setup/** is permitted at the security layer, so every credential and setup-state check
 * happens in {@link SetupWizardService}; the bearer filter still populates the principal when one is sent.
 */
@RestController
@RequestMapping("/api/setup")
public class SetupWizardController {

    static final String TOKEN_HEADER = "X-Setup-Token";

    private final SetupWizardService wizard;

    public SetupWizardController(SetupWizardService wizard) {
        this.wizard = wizard;
    }

    public record ModelsRequest(UUID chatModelId, UUID transcriptionModelId) {}

    @PostMapping("/sso")
    public SetupWizardService.SsoView sso(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token, @RequestBody SsoRequest req) {
        return wizard.saveSso(token, req);
    }

    /** Recovery only: needs a recovery token issued after setup completed. */
    @PutMapping("/sso")
    public SetupWizardService.SsoView recoverSso(
            @RequestHeader(name = TOKEN_HEADER, required = false) String token, @RequestBody SsoRequest req) {
        return wizard.recoverSso(token, req);
    }

    @PostMapping("/provider")
    @ResponseStatus(HttpStatus.CREATED)
    public ProviderView provider(
            @AuthenticationPrincipal AppUser user, @RequestBody(required = false) ProviderRequest req) {
        return ProviderController.view(wizard.createSystemProvider(user, req));
    }

    @PostMapping("/models")
    public ModelsRequest models(
            @AuthenticationPrincipal AppUser user, @RequestBody(required = false) ModelsRequest req) {
        ModelsRequest r = req == null ? new ModelsRequest(null, null) : req;
        wizard.setDefaultModels(user, r.chatModelId(), r.transcriptionModelId());
        return r;
    }

    @PostMapping("/finish")
    public Map<String, Boolean> finish(@AuthenticationPrincipal AppUser user) {
        wizard.finish(user);
        return Map.of("complete", true);
    }
}
