package com.muninn.auth;

import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Decides whether an identity from the IdP may use this instance. Deny by default. */
@Component
public class DomainPolicy {

    private final AuthSettings settings;

    public DomainPolicy(AuthSettings settings) {
        this.settings = settings;
    }

    /**
     * @param email         email claim from a validated ID token
     * @param emailVerified whether the IdP vouches for the email
     * @param tenantId      Entra {@code tid} claim, or null
     */
    public boolean allows(String email, boolean emailVerified, String tenantId) {
        if (!emailVerified || !domainAllowed(email)) {
            return false;
        }
        Optional<String> pinned = settings.tenantId();
        return pinned.isEmpty() || (tenantId != null && pinned.get().equalsIgnoreCase(tenantId.trim()));
    }

    /** Whether the email's domain is currently on the allow list (exact, case-insensitive). */
    public boolean domainAllowed(String email) {
        if (email == null) {
            return false;
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            return false;
        }
        return settings.allowedDomains().contains(email.substring(at + 1).toLowerCase(Locale.ROOT));
    }
}
