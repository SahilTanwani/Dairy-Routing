package com.dairy.milkroute.error;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A device clock too far out to trust.
 *
 * <p>422 rather than 400: the request is well formed and the server understood it, but
 * acting on it would write a wrong {@code first_collection_at} and therefore a wrong
 * spoilage deadline. The app is being told to resync, not that it sent nonsense.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClockDriftException extends RuntimeException {

    public ClockDriftException(String message) {
        super(message);
    }
}
