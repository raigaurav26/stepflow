package com.stepflow.definition;

/**
 * Throw from a step handler when trying again cannot help, for example a declined card or
 * invalid input. The step goes straight to DEAD instead of using up its retries.
 */
public class NonRetryableException extends RuntimeException {

    public NonRetryableException(String message) {
        super(message);
    }

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
