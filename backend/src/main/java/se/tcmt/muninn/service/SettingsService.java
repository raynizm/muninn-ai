package se.tcmt.muninn.service;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import se.tcmt.muninn.model.TaskType;

/** Admin-editable instance settings. Access control (ADMIN only) is enforced in {@code SecurityConfig}. */
@Service
public class SettingsService {

    /** Never carries the OIDC client secret. */
    public record SettingsView(
            boolean userOverrideEnabled,
            List<String> allowedDomains,
            UUID defaultChatModelId,
            UUID defaultTranscriptionModelId,
            boolean oidcConfigured,
            String issuer,
            String clientId,
            String tenantId) {}

    /** Null fields are left unchanged. */
    public record SettingsUpdate(
            Boolean userOverrideEnabled,
            List<String> allowedDomains,
            UUID defaultChatModelId,
            UUID defaultTranscriptionModelId) {}

    private final AuthSettings auth;
    private final ProviderService providers;
    private final TransactionTemplate tx;

    public SettingsService(AuthSettings auth, ProviderService providers, TransactionTemplate tx) {
        this.auth = auth;
        this.providers = providers;
        this.tx = tx;
    }

    public SettingsView get() {
        return new SettingsView(
                providers.overrideEnabled(),
                auth.allowedDomains().stream().sorted().toList(),
                providers.defaultModelId(TaskType.CHAT).orElse(null),
                providers.defaultModelId(TaskType.TRANSCRIPTION).orElse(null),
                auth.oidcConfigured(),
                auth.issuer().orElse(null),
                auth.clientId().orElse(null),
                auth.tenantId().orElse(null));
    }

    /** Validates everything first, then writes in one transaction: a 400 never leaves a partial update. */
    public SettingsView update(SettingsUpdate u) {
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        List<String> domains = u.allowedDomains() == null ? null : AuthSettings.normalizeDomains(u.allowedDomains());
        requireUsable(u.defaultChatModelId(), TaskType.CHAT, "defaultChatModelId");
        requireUsable(u.defaultTranscriptionModelId(), TaskType.TRANSCRIPTION, "defaultTranscriptionModelId");
        tx.executeWithoutResult(s -> {
            if (u.userOverrideEnabled() != null) {
                providers.setOverrideEnabled(u.userOverrideEnabled());
            }
            if (domains != null) {
                auth.saveAllowedDomains(domains);
            }
            if (u.defaultChatModelId() != null) {
                providers.setDefaultModel(TaskType.CHAT, u.defaultChatModelId());
            }
            if (u.defaultTranscriptionModelId() != null) {
                providers.setDefaultModel(TaskType.TRANSCRIPTION, u.defaultTranscriptionModelId());
            }
        });
        return get();
    }

    private void requireUsable(UUID modelId, TaskType task, String field) {
        if (modelId != null && !providers.isUsableSystemModel(modelId, task)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    field + " must be an enabled model of an enabled system provider with the right capability");
        }
    }
}
