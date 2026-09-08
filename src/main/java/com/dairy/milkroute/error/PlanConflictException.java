package com.dairy.milkroute.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A publish that the database refused, or that the plan's own state rules out.
 *
 * <p>409 rather than 500: two dispatchers publishing at the same moment is a legitimate
 * thing to happen, and the loser needs to be told which plan won rather than shown a stack
 * trace.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class PlanConflictException extends RuntimeException {

    public PlanConflictException(String message) {
        super(message);
    }

    public PlanConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
