package com.dairy.milkroute.domain.trip;

import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.enums.TripStatus;
import java.util.Map;
import java.util.Set;

/**
 * The states a trip may move between, as one table.
 *
 * <p>A table rather than a cascade of ifs, because the input is not trustworthy. A driver's
 * phone buffers events through a dead zone and sends them all at once; a batch can arrive
 * containing an arrival at stop nine and a breakdown, in either order, minutes after both
 * happened. Every one of those paths has to be either allowed or refused deliberately, and a
 * map is the only shape where "what can follow RETURNING?" is a question with a visible
 * answer.
 *
 * <p>Three states have no exits. COMPLETED and ABORTED are over; BLOCKED means the trip was
 * never crewed, and reviving it is a new trip rather than a transition, so that ops cannot
 * accidentally start a tanker nobody assigned.
 */
public final class TripStateMachine {

    private static final Map<TripStatus, Set<TripStatus>> ALLOWED = Map.of(
            TripStatus.SCHEDULED, Set.of(
                    TripStatus.IN_PROGRESS, TripStatus.ABORTED, TripStatus.BLOCKED),
            TripStatus.IN_PROGRESS, Set.of(
                    TripStatus.RETURNING, TripStatus.BREAKDOWN, TripStatus.ABORTED),
            TripStatus.RETURNING, Set.of(
                    TripStatus.AT_PLANT, TripStatus.BREAKDOWN, TripStatus.ABORTED),
            TripStatus.AT_PLANT, Set.of(
                    TripStatus.COMPLETED),
            TripStatus.BREAKDOWN, Set.of(
                    TripStatus.RETURNING, TripStatus.ABORTED));

    public void transition(Trip trip, TripStatus target) {
        if (!canTransition(trip.getStatus(), target)) {
            throw new IllegalTransitionException(trip.getStatus(), target);
        }
        trip.setStatus(target);
    }

    /**
     * Whether a move is legal, without making it.
     *
     * <p>The replayer needs this: an event that would move a trip somewhere it cannot go is
     * usually a stale event from a dead zone rather than a bug, and it should be ignored
     * quietly rather than aborting a batch of thirty good ones.
     */
    public boolean canTransition(TripStatus from, TripStatus to) {
        return from != to && allowedFrom(from).contains(to);
    }

    public Set<TripStatus> allowedFrom(TripStatus from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    /** A trip that is going nowhere else: finished, abandoned, or never crewed. */
    public boolean isTerminal(TripStatus status) {
        return allowedFrom(status).isEmpty();
    }
}
