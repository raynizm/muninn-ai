package se.tcmt.muninn.provider.adapter;

/** Outcome of a connection test; {@code message} is short and never contains secrets. */
public record ConnectionTestResult(boolean ok, String message) {}
