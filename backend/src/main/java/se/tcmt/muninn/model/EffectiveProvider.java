package se.tcmt.muninn.model;

import java.util.UUID;
import se.tcmt.muninn.provider.adapter.ProviderConnection;

/** The provider and model a task should run against, with the decrypted connection ready for an adapter. */
public record EffectiveProvider(
        UUID providerId, UUID modelId, String modelIdentifier, ProviderConnection connection, boolean userOwned) {}
