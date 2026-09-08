package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.config.SolverParameters;
import com.dairy.milkroute.config.TravelTimeFactory;
import com.dairy.milkroute.domain.geo.GeoPoint;
import com.dairy.milkroute.domain.tracking.EtaCalculator;
import com.dairy.milkroute.domain.tracking.EtaEstimate;
import com.dairy.milkroute.domain.tracking.PositionResolver;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.repository.TripStopRepository;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wires the tracking domain to the database.
 *
 * <p>{@link PositionResolver} and {@link EtaCalculator} take entities and return numbers;
 * this loads the entities, builds a travel model from the current solver parameters, and
 * writes the answer back onto the trip so the ops board can read it without recomputing.
 */
@Service
public class TripTrackingService {

    private final TripStopRepository tripStopRepo;
    private final TravelTimeFactory travelTimeFactory;
    private final SolverParameters parameters;
    private final ClockProvider clock;
    private final PositionResolver positions = new PositionResolver();

    public TripTrackingService(TripStopRepository tripStopRepo,
                               TravelTimeFactory travelTimeFactory,
                               SolverParameters parameters,
                               ClockProvider clock) {
        this.tripStopRepo = tripStopRepo;
        this.travelTimeFactory = travelTimeFactory;
        this.parameters = parameters;
        this.clock = clock;
    }

    /** When this trip reaches the plant, from where it is now. */
    @Transactional(readOnly = true)
    public EtaEstimate etaToPlant(Trip trip) {
        return calculator().toPlant(trip, stopsOf(trip), plantOf(trip), clock.now());
    }

    /** When this trip reaches one stop. */
    @Transactional(readOnly = true)
    public EtaEstimate etaToStop(Trip trip, int seq) {
        return calculator().toStop(trip, stopsOf(trip), plantOf(trip), seq, clock.now());
    }

    /**
     * Recomputes the ETA and stores it on the trip.
     *
     * <p>Denormalised on purpose. The board polls every couple of seconds for every live
     * trip, and recomputing a full route walk per poll per trip would put the travel model in
     * the hot path of a screen refresh.
     */
    @Transactional
    public EtaEstimate refresh(Trip trip) {
        EtaEstimate estimate = etaToPlant(trip);
        trip.setEtaPlantAt(estimate.at());
        trip.setEtaConfidence(estimate.confidence());
        return estimate;
    }

    /**
     * Built per call, like the travel model, so that retuning a parameter and immediately
     * asking for an ETA uses the new value rather than one captured at startup.
     */
    private EtaCalculator calculator() {
        SolverParameters.Snapshot snapshot = parameters.snapshot();
        return new EtaCalculator(
                travelTimeFactory.create(),
                positions,
                Duration.ofMinutes(snapshot.getInt("pingFreshMinutes")),
                Duration.ofMinutes(snapshot.getInt("trackingLostMinutes")));
    }

    private List<TripStop> stopsOf(Trip trip) {
        return tripStopRepo.findByTripIdOrderBySeqAsc(trip.getId());
    }

    private static GeoPoint plantOf(Trip trip) {
        return new GeoPoint(
                trip.getDestinationPlant().getLat().doubleValue(),
                trip.getDestinationPlant().getLng().doubleValue());
    }
}
