package se.tcmt.muninn.exception;

/**
 * The chat model's output is not a usable summary. The message describes the shape problem, never
 * the output.
 */
public class InvalidSummaryException extends RuntimeException {

  /** Creates the exception with the reason the output was rejected. */
  public InvalidSummaryException(String message) {
    super(message);
  }
}
