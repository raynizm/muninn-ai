package com.muninn.provider;

import java.util.List;
import java.util.UUID;

public record AiModel(UUID id, UUID providerId, String modelIdentifier, List<String> capabilities, boolean enabled) {}
