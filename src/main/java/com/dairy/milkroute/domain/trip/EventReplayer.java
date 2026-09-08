package com.dairy.milkroute.domain.trip;

import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.enums.SkipReason;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.enums.TripStopStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Turns driver events into trip state.
 *
 * <p>Events drive progress; pings only refine position. A tanker that drives past a
 * collection point has not collected from it, and nothing in here is triggered by geography.
 *
 * <h2>Be generous with what arrives, strict about what is recorded</h2>
 *
 * <p>The input is a phone that has been out of signal for forty minutes. Events arrive late,
 * out of order, and occasionally not at all. The replayer's job is to get as much truth out
 * of that as it can:
 *
 * <ul>
 *   <li>A COLLECTED with no arrival before it <strong>synthesises</strong> the arrival rather
 *       than rejecting the event. The milk exists whether or not the arrival event survived
 *       the journey, and losing a milk record — which is money owed to a farmer — is far
 *       worse than an arrival time that is inferred.
 *   <li>A driver who reports stop nine while the system thinks he is at seven is right and
 *       the system is wrong. He is standing there. Stops seven and eight are marked skipped
 *       so ops can see it, and he is not blocked.
 *   <li>A state change that is not legal from where the trip is gets ignored, not thrown.
 *       A stale TRIP_STARTED replayed from a dead zone should not abort a batch containing
 *       thirty good events.
 * </ul>
 *
 * <p>What it will not do is invent milk. Litres come only from a COLLECTED that carries them.
 */
public final class EventReplayer {

    private final TripStateMachine stateMachine;
    private final CollectionSink collections;

    public EventReplayer(TripStateMachine stateMachine, CollectionSink collections) {
        this.stateMachine = stateMachine;
        this.collections = collections;
    }

    /**
     * Applies one event to a trip and its stops.
     *
     * @param trip  the trip, already loaded
     * @param stops its stops in sequence order
     * @param event what the driver reported
     * @return the stop the event touched, or null for a whole-trip event
     */
    public TripStop apply(Trip trip, List<TripStop> stops, ReplayableEvent event) {
        return switch (event.type()) {
            case TRIP_STARTED -> {
                move(trip, TripStatus.IN_PROGRESS);
                if (trip.getStartedAt() == null) {
                    trip.setStartedAt(event.clientTs());
                }
                yield null;
            }
            case ARRIVED_AT_STOP -> arrive(trip, stops, event, event.clientTs());
            case COLLECTED -> collect(trip, stops, event);
            case DEPARTED_STOP -> {
                TripStop stop = stopFor(stops, event);
                if (stop != null) {
                    stop.setDepartedAt(event.clientTs());
                    // Leaving the last village is the moment a tanker starts for home.
                    // Without this a trip has no way to reach RETURNING at all: there is no
                    // driver event meaning "heading back", and a phone reporting arrival at
                    // the plant straight from IN_PROGRESS would be making an illegal move.
                    if (nothingLeftToCollect(stops)) {
                        move(trip, TripStatus.RETURNING);
                    }
                }
                yield stop;
            }
            case STOP_SKIPPED -> skip(stops, event);
            case TANKER_FULL -> {
                // Everything still ahead becomes a second trip's problem, not a lost one.
                deferRemaining(trip, stops);
                move(trip, TripStatus.RETURNING);
                yield null;
            }
            case BREAKDOWN -> {
                move(trip, TripStatus.BREAKDOWN);
                yield null;
            }
            case ARRIVED_AT_PLANT -> {
                // A tanker at the plant was self-evidently on its way there, so a missing
                // departure event is inferred rather than allowed to strand the trip.
                move(trip, TripStatus.RETURNING);
                move(trip, TripStatus.AT_PLANT);
                trip.setPlantArrivalAt(event.clientTs());
                yield null;
            }
            case UNLOADED -> {
                move(trip, TripStatus.COMPLETED);
                trip.setCompletedAt(event.clientTs());
                yield null;
            }
            case TRIP_ABORTED -> {
                move(trip, TripStatus.ABORTED);
                trip.setCompletedAt(event.clientTs());
                yield null;
            }
            case ROUTE_DIVERTED -> null;
        };
    }

    private TripStop arrive(Trip trip, List<TripStop> stops, ReplayableEvent event, Instant at) {
        TripStop stop = stopFor(stops, event);
        if (stop == null) {
            return null;
        }

        // A trip whose first event is an arrival is a trip whose TRIP_STARTED was lost.
        if (trip.getStatus() == TripStatus.SCHEDULED) {
            move(trip, TripStatus.IN_PROGRESS);
            if (trip.getStartedAt() == null) {
                trip.setStartedAt(at);
            }
        }

        skipEverythingBefore(stops, stop);

        if (stop.getArrivedAt() == null) {
            stop.setArrivedAt(at);
        }
        if (stop.getStatus() == TripStopStatus.PENDING
                || stop.getStatus() == TripStopStatus.EN_ROUTE) {
            stop.setStatus(TripStopStatus.ARRIVED);
        }
        trip.setCurrentSeq(stop.getSeq());
        return stop;
    }

    private TripStop collect(Trip trip, List<TripStop> stops, ReplayableEvent event) {
        TripStop stop = stopFor(stops, event);
        if (stop == null) {
            return null;
        }

        // The edge case worth naming: no arrival ever came. Rather than refuse the milk,
        // infer when he must have got there — service time before he finished — and carry on.
        if (stop.getArrivedAt() == null) {
            Instant synthesised = event.clientTs().minus(serviceTimeOf(stop));
            arrive(trip, stops, event, synthesised);
        } else {
            arrive(trip, stops, event, stop.getArrivedAt());
        }

        BigDecimal litres = collections.record(stop, event.litresByFarmer(), event.clientTs());

        stop.setActualLitres(litres);
        stop.setStatus(TripStopStatus.COLLECTED);
        if (stop.getDepartedAt() == null) {
            stop.setDepartedAt(event.clientTs());
        }

        // The spoilage clock starts at the first collection, not at departure: the run out
        // to the first village carries no milk. Written once and never moved.
        if (trip.getFirstCollectionAt() == null) {
            trip.setFirstCollectionAt(event.clientTs());
            trip.setSpoilageDeadlineAt(
                    event.clientTs().plus(Duration.ofMinutes(trip.getHoldBudgetMinutes())));
        }

        trip.setLitresOnBoard(trip.getLitresOnBoard().add(litres));
        return stop;
    }

    private TripStop skip(List<TripStop> stops, ReplayableEvent event) {
        TripStop stop = stopFor(stops, event);
        if (stop == null || stop.getStatus() == TripStopStatus.COLLECTED) {
            return stop;
        }
        stop.setStatus(TripStopStatus.SKIPPED);
        stop.setSkipReason(skipReasonOf(event));
        return stop;
    }

    /**
     * Marks everything the driver has passed as skipped.
     *
     * <p>He is at stop nine and the system thought seven. He is the one standing in the
     * village, so the system is what is wrong; seven and eight are recorded as skipped, with
     * a reason, and he is not held up. Silently leaving them PENDING would have the farmer
     * endpoint promising a tanker that has already gone by.
     */
    private void skipEverythingBefore(List<TripStop> stops, TripStop reached) {
        for (TripStop earlier : stops) {
            if (earlier.getSeq() >= reached.getSeq()) {
                continue;
            }
            if (earlier.getStatus() == TripStopStatus.PENDING
                    || earlier.getStatus() == TripStopStatus.EN_ROUTE) {
                earlier.setStatus(TripStopStatus.SKIPPED);
                earlier.setSkipReason(SkipReason.SEQUENCE_SKIP);
            }
        }
    }

    private void deferRemaining(Trip trip, List<TripStop> stops) {
        for (TripStop stop : stops) {
            if (stop.getSeq() > trip.getCurrentSeq()
                    && (stop.getStatus() == TripStopStatus.PENDING
                    || stop.getStatus() == TripStopStatus.EN_ROUTE)) {
                stop.setStatus(TripStopStatus.DEFERRED);
                stop.setSkipReason(SkipReason.TANKER_FULL);
            }
        }
    }

    /**
     * Moves the trip if the move is legal, and does nothing if it is not.
     *
     * <p>Quiet on purpose. A replayed batch can contain an event the trip has already moved
     * past, and refusing the whole batch over it would lose the twenty-nine events that are
     * still news.
     */
    private void move(Trip trip, TripStatus target) {
        if (stateMachine.canTransition(trip.getStatus(), target)) {
            stateMachine.transition(trip, target);
        }
    }

    /** True once every stop has been collected from, skipped or deferred. */
    private static boolean nothingLeftToCollect(List<TripStop> stops) {
        return stops.stream().noneMatch(stop ->
                stop.getStatus() == TripStopStatus.PENDING
                        || stop.getStatus() == TripStopStatus.EN_ROUTE
                        || stop.getStatus() == TripStopStatus.ARRIVED);
    }

    private static TripStop stopFor(List<TripStop> stops, ReplayableEvent event) {
        if (event.seq() == null) {
            return null;
        }
        return stops.stream()
                .filter(stop -> stop.getSeq() == event.seq())
                .findFirst()
                .orElse(null);
    }

    private static Duration serviceTimeOf(TripStop stop) {
        return Duration.ofSeconds(Math.round(
                stop.getCollectionPoint().getServiceMinutes().doubleValue() * 60));
    }

    private static SkipReason skipReasonOf(ReplayableEvent event) {
        if (event.skipReasonName() == null) {
            return SkipReason.NO_MILK;
        }
        try {
            return SkipReason.valueOf(event.skipReasonName());
        } catch (IllegalArgumentException unknown) {
            return SkipReason.NO_MILK;
        }
    }
}
