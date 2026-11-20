package se.tcmt.muninn.model;

/** User-facing processing state of a recording (separate from its upload status and from job states). */
public enum ProcessingStatus {
    PENDING,
    TRANSCRIBING,
    SUMMARIZING,
    COMPLETED,
    FAILED
}
