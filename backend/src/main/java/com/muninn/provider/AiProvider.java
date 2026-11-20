package com.muninn.provider;

import java.time.Instant;
import java.util.UUID;

/** ai_provider row. {@code scope} is the stored value: GLOBAL (the "system" provider) or USER. */
public record AiProvider(
        UUID id,
        String scope,
        UUID ownerUserId,
        String name,
        String baseUrl,
        String encryptedKey,
        boolean enabled,
        Instant createdAt,
        Boolean lastTestOk,
        String lastTestMessage,
        Instant lastTestedAt) {

    public boolean isSystem() {
        return "GLOBAL".equals(scope);
    }

    public boolean keySet() {
        return encryptedKey != null && !encryptedKey.isEmpty();
    }

    @Override
    public String toString() {
        return "AiProvider[id=" + id + ", scope=" + scope + ", name=" + name + "]";
    }
}
