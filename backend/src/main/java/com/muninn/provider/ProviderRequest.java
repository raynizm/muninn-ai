package com.muninn.provider;

/**
 * Create/update payload. {@code apiKey}: null keeps the stored key on update, empty clears it.
 * {@code scope} is only read by the API ("SYSTEM" or "USER"); {@code enabled} null means true/unchanged.
 */
public record ProviderRequest(String name, String baseUrl, String apiKey, Boolean enabled, String scope) {

    public ProviderRequest(String name, String baseUrl, String apiKey, Boolean enabled) {
        this(name, baseUrl, apiKey, enabled, null);
    }

    @Override
    public String toString() {
        return "ProviderRequest[name=" + name + ", baseUrl=" + baseUrl + ", apiKey=***]";
    }
}
