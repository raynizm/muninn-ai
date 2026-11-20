package com.muninn.provider;

import com.muninn.provider.adapter.ProviderConnection;
import java.util.UUID;

/** The provider and model a task should run against, with the decrypted connection ready for an adapter. */
public record EffectiveProvider(
        UUID providerId, UUID modelId, String modelIdentifier, ProviderConnection connection, boolean userOwned) {}
