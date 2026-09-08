package com.dairy.milkroute.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a dataset name does not resolve to a file on the classpath.
 *
 * <p>Carries a 404 directly so that a mistyped dataset name in a demo reads as "no such
 * dataset" rather than a stack trace. It matters most on the reseed path, where the
 * config is loaded before anything is truncated: a bad name has to fail while the
 * database is still intact.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class DatasetNotFoundException extends RuntimeException {

    public DatasetNotFoundException(String message) {
        super(message);
    }

    public DatasetNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
