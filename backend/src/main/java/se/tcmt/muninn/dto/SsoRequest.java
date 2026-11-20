package se.tcmt.muninn.dto;

import java.util.List;

/** SSO configuration from the wizard or recovery. {@code tenantId} is optional; the secret is never echoed. */
public record SsoRequest(
        String issuer, String clientId, String clientSecret, List<String> allowedDomains, String tenantId) {

    @Override
    public String toString() {
        return "SsoRequest[issuer=" + issuer + ", clientId=" + clientId + ", clientSecret=***, allowedDomains="
                + allowedDomains + ", tenantId=" + tenantId + "]";
    }
}
