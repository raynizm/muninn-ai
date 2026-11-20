package se.tcmt.muninn.model;

import java.time.Instant;
import java.util.UUID;

/** A recording row; {@code lastError} is set only while the recording is FAILED. */
public record Recording(
        UUID id,
        UUID userId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Long durationMs,
        String mime,
        Long sizeBytes,
        String sha256,
        String uploadStatus,
        ProcessingStatus processingStatus,
        String lastError) {}
