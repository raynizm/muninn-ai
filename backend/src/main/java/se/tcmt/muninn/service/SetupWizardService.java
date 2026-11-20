package se.tcmt.muninn.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.dto.ProviderRequest;
import se.tcmt.muninn.dto.SsoRequest;
import se.tcmt.muninn.model.AiProvider;
import se.tcmt.muninn.model.AppUser;
import se.tcmt.muninn.model.TaskType;

/**
 * First-run wizard. Credentials per step, all only while setup is incomplete (404 afterwards):
 * <ul>
 *   <li>{@code saveSso}: the setup token (the admin cannot sign in before SSO exists);</li>
 *   <li>{@code createSystemProvider}, {@code setDefaultModels}, {@code finish}: an ADMIN bearer token. Before
 *       completion the only possible ADMIN is the first admin, created by the setup-token callback.</li>
 * </ul>
 * After completion only {@code recoverSso} works, with a single-use recovery token.
 */
@Service
public class SetupWizardService {

    public record SsoView(String issuer, String clientId, List<String> allowedDomains, String tenantId) {}

    private record ValidSso(
            String issuer, String clientId, String clientSecret, List<String> domains, String tenantId) {}

    private final SetupService setup;
    private final AuthSettings auth;
    private final ProviderService providers;
    private final TransactionTemplate tx;

    public SetupWizardService(
            SetupService setup, AuthSettings auth, ProviderService providers, TransactionTemplate tx) {
        this.setup = setup;
        this.auth = auth;
        this.providers = providers;
        this.tx = tx;
    }

    public SsoView saveSso(String setupToken, SsoRequest req) {
        requireIncomplete();
        if (!setup.verifyToken(setupToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid setup token");
        }
        ValidSso sso = validate(req);
        tx.executeWithoutResult(s -> apply(sso));
        return view(sso);
    }

    /** Recovery after completion: replaces the SSO config and consumes the recovery token (single use). */
    public SsoView recoverSso(String recoveryToken, SsoRequest req) {
        if (!setup.isComplete()) {
            throw notFound();
        }
        if (!setup.verifyRecoveryToken(recoveryToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid recovery token");
        }
        ValidSso sso = validate(req); // a bad body does not burn the token
        tx.executeWithoutResult(s -> {
            if (!setup.consumeRecoveryToken(recoveryToken)) { // lost a race with a concurrent use
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid recovery token");
            }
            apply(sso);
        });
        return view(sso);
    }

    public AiProvider createSystemProvider(AppUser actor, ProviderRequest req) {
        requireIncompleteAndAdmin(actor);
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        return providers.create(null, req);
    }

    public void setDefaultModels(AppUser actor, UUID chatModelId, UUID transcriptionModelId) {
        requireIncompleteAndAdmin(actor);
        if (!providers.isUsableSystemModel(chatModelId, TaskType.CHAT)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "chatModelId must be an enabled chat model of the system provider");
        }
        if (!providers.isUsableSystemModel(transcriptionModelId, TaskType.TRANSCRIPTION)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "transcriptionModelId must be an enabled transcription model of the system provider");
        }
        tx.executeWithoutResult(s -> {
            providers.setDefaultModel(TaskType.CHAT, chatModelId);
            providers.setDefaultModel(TaskType.TRANSCRIPTION, transcriptionModelId);
        });
    }

    public void finish(AppUser actor) {
        requireIncompleteAndAdmin(actor);
        if (auth.allowedDomains().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "allowed domains must not be empty");
        }
        if (!providers.hasSystemProvider()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a system provider is required");
        }
        for (TaskType task : TaskType.values()) {
            boolean ok = providers
                    .defaultModelId(task)
                    .map(id -> providers.isUsableSystemModel(id, task))
                    .orElse(false);
            if (!ok) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "a default " + task.name().toLowerCase(Locale.ROOT) + " model is required");
            }
        }
        setup.markComplete();
    }

    private void requireIncomplete() {
        if (setup.isComplete()) {
            throw notFound();
        }
    }

    /** 404 once complete (before looking at credentials), then 401 without a user, 403 for non-admins. */
    private void requireIncompleteAndAdmin(AppUser actor) {
        requireIncomplete();
        if (actor == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign in required");
        }
        if (actor.role() != AppUser.Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        }
    }

    private void apply(ValidSso sso) {
        auth.saveOidc(sso.issuer(), sso.clientId(), sso.clientSecret());
        auth.saveAllowedDomains(sso.domains());
        auth.saveTenantId(sso.tenantId());
    }

    private static SsoView view(ValidSso sso) {
        return new SsoView(sso.issuer(), sso.clientId(), sso.domains(), sso.tenantId());
    }

    private static ValidSso validate(SsoRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        if (isBlank(req.clientId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientId is required");
        }
        if (isBlank(req.clientSecret())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clientSecret is required");
        }
        String tenant = isBlank(req.tenantId()) ? null : req.tenantId().trim();
        return new ValidSso(
                validateIssuer(req.issuer()),
                req.clientId().trim(),
                req.clientSecret(),
                AuthSettings.normalizeDomains(req.allowedDomains()),
                tenant);
    }

    /** https with a host and no credentials, query or fragment; plain http only for localhost/127.0.0.1. */
    static String validateIssuer(String issuer) {
        if (isBlank(issuer)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issuer is required");
        }
        String trimmed = issuer.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issuer is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issuer is not a valid URL");
        }
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issuer must use https");
        }
        return trimmed;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
