package com.muninn.processing;

/** The chat model's output is not a usable summary. The message describes the shape problem, never the output. */
public class InvalidSummaryException extends RuntimeException {

    public InvalidSummaryException(String message) {
        super(message);
    }
}
