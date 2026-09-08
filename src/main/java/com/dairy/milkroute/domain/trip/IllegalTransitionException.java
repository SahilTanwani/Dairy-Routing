package com.dairy.milkroute.domain.trip;

import com.dairy.milkroute.enums.TripStatus;

/**
 * A trip was asked to move to a state it cannot reach from where it is.
 *
 * <p>Plain Java, thrown from {@code domain/}. Mapping it to an HTTP status is the web
 * layer's business, not the state machine's.
 */
public class IllegalTransitionException extends RuntimeException {

    private final transient TripStatus from;
    private final transient TripStatus to;

    public IllegalTransitionException(TripStatus from, TripStatus to) {
        super("a trip cannot go from %s to %s".formatted(from, to));
        this.from = from;
        this.to = to;
    }

    public TripStatus from() {
        return from;
    }

    public TripStatus to() {
        return to;
    }
}
