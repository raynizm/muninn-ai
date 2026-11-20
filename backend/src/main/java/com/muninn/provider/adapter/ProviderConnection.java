package com.muninn.provider.adapter;

/** Where and how to talk to a provider. {@code apiKey} may be null (keyless local endpoints). */
public record ProviderConnection(String baseUrl, String apiKey) {

    @Override
    public String toString() {
        return "ProviderConnection[baseUrl=" + baseUrl + ", apiKey=***]";
    }
}
