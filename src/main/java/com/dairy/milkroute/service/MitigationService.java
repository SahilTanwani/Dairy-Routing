package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.domain.mitigation.ContinueAsPlanned;
import com.dairy.milkroute.domain.mitigation.Mitigation;
import com.dairy.milkroute.domain.mitigation.SkipRemaining;
import com.dairy.milkroute.domain.tracking.EtaEstimate;
import com.dairy.milkroute.domain.trip.TripStateMachine;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.enums.SkipReason;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.enums.TripStopStatus;
import com.dairy.milkroute.error.PlanConflictException;
import com.dairy.milkroute.error.TripNotFoundException;
import com.dairy.milkroute.repository.TripRepository;
import com.dairy.milkroute.repository.TripStopRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works out what could be done about a trip in trouble, and does none of it.
 *
 * <p><strong>Generated and ranked, never executed automatically.</strong> Each option here
 * decides whose milk gets collected and whose is left at the roadside, and the information
 * that actually settles that is not in this database: which village complained last week,
 * whether the plant manager will hold the gate, how new the driver is. A system that acted
 * on its own would be making a human decision with half the facts and none of the
 * accountability.
 *
 * <p>Ranked by litres saved, and {@link ContinueAsPlanned} is always in the list with its
 * risk spelled out. Showing only the cautious options would be a recommendation wearing the
 * costume of a menu.
 *
 * <p><strong>When nothing saves the load, it says so.</strong> A system that always has a
 * fix is lying, and the first time a dispatcher catches it inventing one they stop believing
 * the rest.
 */
@Service
public class MitigationService {

    private final TripRepository tripRepo;
    private final TripStopRepository tripStopRepo;
    private final TripTrackingService tracking;
    private final ClockProvider clock;
    private final TripStateMachine stateMachine = new TripStateMachine();

    public MitigationService(TripRepository tripRepo,
                             TripStopRepository tripStopRepo,
                             TripTrackingService tracking,
                             ClockProvider clock) {
        this.tripRepo = tripRepo;
        this.tripStopRepo = tripStopRepo;
        this.tracking = tracking;
        this.clock = clock;
    }

    /** Every option for this trip, best first. */
    @Transactional(readOnly = true)
    public List<Mitigation> generate(long tripId) {
        Trip trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("no trip with id " + tripId));

        List<TripStop> stops = tripStopRepo.findByTripIdOrderBySeqAsc(tripId);
        List<Mitigation> options = new ArrayList<>();

        options.add(continueAsPlanned(trip));

        SkipRemaining skip = skipRemaining(trip, stops);
        if (skip != null) {
            options.add(skip);
        }

        options.sort(Comparator.comparingDouble(Mitigation::litresSaved).reversed());
        return options;
    }

    /** The baseline: finish the route, and here is what that costs. */
    private ContinueAsPlanned continueAsPlanned(Trip trip) {
        EtaEstimate eta = tracking.etaToPlant(trip);
        long ageMin = milkAgeAt(trip, eta.at());
        boolean meets = ageMin <= trip.getHoldBudgetMinutes();

        return new ContinueAsPlanned(
                trip.getLitresOnBoard().doubleValue(),
                eta.at(),
                (int) ageMin,
                meets,
                meets ? 0 : ageMin - trip.getHoldBudgetMinutes());
    }

    /**
     * Abandon what is ahead, run the load home.
     *
     * <p>Null when there is nothing left to skip — offering to skip zero stops would be
     * padding the list to look helpful.
     */
    private SkipRemaining skipRemaining(Trip trip, List<TripStop> stops) {
        List<TripStop> ahead = stops.stream().filter(this::stillToDo).toList();
        if (ahead.isEmpty()) {
            return null;
        }

        // Straight home from where it is: no more service time, no more detours.
        EtaEstimate direct = tracking.etaToPlant(trip);
        long minutesAhead = ahead.stream()
                .mapToLong(stop -> Math.round(
                        stop.getCollectionPoint().getServiceMinutes().doubleValue()))
                .sum();
        Instant arrival = direct.at().minus(Duration.ofMinutes(minutesAhead));

        long ageMin = milkAgeAt(trip, arrival);
        double forgone = ahead.stream()
                .mapToDouble(stop -> stop.getPlannedLitres().doubleValue())
                .sum();

        return new SkipRemaining(
                ahead.size(),
                forgone,
                trip.getLitresOnBoard().doubleValue(),
                arrival,
                (int) ageMin,
                ageMin <= trip.getHoldBudgetMinutes(),
                ahead.stream().map(stop -> stop.getCollectionPoint().getCode()).toList());
    }

    /**
     * Applies a chosen option.
     *
     * <p>Guarded by the trip's {@code version}. Two dispatchers acting on the same trip in
     * the same moment is not hypothetical on a bad evening; the second one gets a clean 409
     * telling them the trip moved, rather than half of one plan and half of another.
     */
    @Transactional
    public Trip execute(long tripId, String action, int expectedVersion) {
        Trip trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("no trip with id " + tripId));

        if (trip.getVersion() != expectedVersion) {
            throw new PlanConflictException(
                    ("trip %d has moved on since you looked at it (version %d, you had %d). "
                            + "Reload and decide again.")
                            .formatted(tripId, trip.getVersion(), expectedVersion));
        }

        try {
            return switch (action.toUpperCase()) {
                case "SKIP_REMAINING" -> applySkipRemaining(trip);
                case "CONTINUE_AS_PLANNED" -> trip;
                default -> throw new IllegalArgumentException(
                        "unknown mitigation '%s'".formatted(action));
            };
        } catch (ObjectOptimisticLockingFailureException raced) {
            throw new PlanConflictException(
                    "trip %d was changed by someone else; reload and decide again"
                            .formatted(tripId), raced);
        }
    }

    private Trip applySkipRemaining(Trip trip) {
        List<TripStop> stops = tripStopRepo.findByTripIdOrderBySeqAsc(trip.getId());

        for (TripStop stop : stops) {
            if (stillToDo(stop)) {
                // DEFERRED rather than SKIPPED: this milk was not refused, it was postponed,
                // and the farmer endpoint says different things about the two.
                stop.setStatus(TripStopStatus.DEFERRED);
                stop.setSkipReason(SkipReason.SPOILAGE_ABORT);
            }
        }
        tripStopRepo.saveAll(stops);

        if (stateMachine.canTransition(trip.getStatus(), TripStatus.RETURNING)) {
            stateMachine.transition(trip, TripStatus.RETURNING);
        }
        return tripRepo.save(trip);
    }

    private boolean stillToDo(TripStop stop) {
        return stop.getStatus() == TripStopStatus.PENDING
                || stop.getStatus() == TripStopStatus.EN_ROUTE;
    }

    private long milkAgeAt(Trip trip, Instant arrival) {
        Instant from = trip.getFirstCollectionAt() != null
                ? trip.getFirstCollectionAt()
                : clock.now();
        return Duration.between(from, arrival).toMinutes();
    }
}
