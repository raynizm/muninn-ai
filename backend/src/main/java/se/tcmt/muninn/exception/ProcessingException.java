package se.tcmt.muninn.exception;

/**
 * A processing attempt failed for a known reason. The message is authored here, short and secret-free, and is
 * shown to the user verbatim as the recording's {@code lastError}; never pass provider or exception text in.
 */
public class ProcessingException extends RuntimeException {

    public ProcessingException(String userFacingMessage) {
        super(userFacingMessage, null, false, false);
    }
}
