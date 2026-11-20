package se.tcmt.muninn.model;

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

  /** Whether this is a system-wide provider rather than a user's own. */
  public boolean isSystem() {
    return "GLOBAL".equals(scope);
  }

  /** Whether an API key is stored. */
  public boolean keySet() {
    return encryptedKey != null && !encryptedKey.isEmpty();
  }

  /** Masks the encrypted key. */
  @Override
  public String toString() {
    return "AiProvider[id=" + id + ", scope=" + scope + ", name=" + name + "]";
  }
}
