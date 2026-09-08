package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.dto.request.TankerPingRequest;
import com.dairy.milkroute.dto.response.TripResponse;
import com.dairy.milkroute.entity.Driver;
import com.dairy.milkroute.entity.TankerPing;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.enums.Session;
import com.dairy.milkroute.error.TripNotFoundException;
import com.dairy.milkroute.repository.DriverRepository;
import com.dairy.milkroute.repository.FarmerRepository;
import com.dairy.milkroute.repository.TankerPingRepository;
import com.dairy.milkroute.repository.TripRepository;
import com.dairy.milkroute.repository.TripStopRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a driver's phone asks for and sends back.
 *
 * <p>Reads come entirely from {@code trip_stop}, never from the plan. A driver halfway down
 * his list must keep seeing the list he started with, whatever ops has published since.
 */
@Service
public class DriverTripService {

    private final TripRepository tripRepo;
    private final TripStopRepository tripStopRepo;
    private final DriverRepository driverRepo;
    private final FarmerRepository farmerRepo;
    private final TankerPingRepository pingRepo;
    private final ClockProvider clock;

    public DriverTripService(TripRepository tripRepo,
                             TripStopRepository tripStopRepo,
                             DriverRepository driverRepo,
                             FarmerRepository farmerRepo,
                             TankerPingRepository pingRepo,
                             ClockProvider clock) {
        this.tripRepo = tripRepo;
        this.tripStopRepo = tripStopRepo;
        this.driverRepo = driverRepo;
        this.farmerRepo = farmerRepo;
        this.pingRepo = pingRepo;
        this.clock = clock;
    }

    /** The trip this driver is running for a session on a date. */
    @Transactional(readOnly = true)
    public TripResponse todaysTrip(String driverCode, LocalDate businessDate, Session session) {
        Driver driver = driverRepo.findByCode(driverCode)
                .orElseThrow(() -> new TripNotFoundException("no driver with code " + driverCode));

        Trip trip = tripRepo
                .findByDriverIdAndBusinessDateAndSession(driver.getId(), businessDate, session)
                .orElseThrow(() -> new TripNotFoundException(
                        "no %s trip for driver %s on %s"
                                .formatted(session, driverCode, businessDate)));

        return toResponse(trip);
    }

    @Transactional(readOnly = true)
    public TripResponse byId(long tripId) {
        return toResponse(tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("no trip with id " + tripId)));
    }

    /**
     * Records GPS readings and updates the trip's last known position.
     *
     * <p>The denormalised position on the trip is what the ops board reads. Twenty-two trips
     * polled every couple of seconds cannot each run an aggregate over a ping table that
     * grows by eight thousand rows a session.
     *
     * <p>Only a reading newer than the one already stored moves it: a batch flushed after a
     * dead zone arrives in whatever order the phone had it, and an old fix must not drag the
     * tanker backwards on the board.
     */
    @Transactional
    public int recordPings(long tripId, List<TankerPingRequest> batch) {
        Trip trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("no trip with id " + tripId));

        List<TankerPing> rows = new ArrayList<>(batch.size());
        for (TankerPingRequest request : batch) {
            TankerPing ping = new TankerPing();
            ping.setTrip(trip);
            ping.setLat(request.lat());
            ping.setLng(request.lng());
            ping.setAccuracyM(request.accuracyM());
            ping.setRecordedAt(request.recordedAt());
            ping.setReceivedAt(clock.now());
            rows.add(ping);

            if (trip.getLastPingAt() == null || request.recordedAt().isAfter(trip.getLastPingAt())) {
                trip.setLastLat(request.lat());
                trip.setLastLng(request.lng());
                trip.setLastPingAt(request.recordedAt());
            }
        }

        pingRepo.saveAll(rows);
        tripRepo.save(trip);
        return rows.size();
    }

    private TripResponse toResponse(Trip trip) {
        List<TripStop> stops = tripStopRepo.findByTripIdOrderBySeqAsc(trip.getId());

        List<TripResponse.Stop> rendered = stops.stream().map(stop -> {
            List<TripResponse.Farmer> farmers = farmerRepo
                    .findByCollectionPointIdAndActiveTrueOrderByCodeAsc(
                            stop.getCollectionPoint().getId())
                    .stream()
                    .map(farmer -> new TripResponse.Farmer(farmer.getCode(), farmer.getName()))
                    .toList();

            return new TripResponse.Stop(
                    stop.getSeq(),
                    stop.getCollectionPoint().getCode(),
                    stop.getCollectionPoint().getVillage().getName(),
                    stop.getStatus().name(),
                    stop.getPlannedArrivalAt(),
                    stop.getArrivedAt(),
                    stop.getPlannedLitres(),
                    stop.getActualLitres(),
                    stop.getSkipReason() == null ? null : stop.getSkipReason().name(),
                    farmers);
        }).toList();

        return new TripResponse(
                trip.getId(),
                trip.getStatus().name(),
                trip.getBusinessDate(),
                trip.getSession().name(),
                trip.getTanker().getRegNo(),
                trip.getDriver().getCode(),
                trip.getCapacityLitres(),
                trip.getLitresOnBoard(),
                trip.getHoldBudgetMinutes(),
                trip.getFirstCollectionAt(),
                trip.getSpoilageDeadlineAt(),
                trip.getCurrentSeq(),
                rendered.size(),
                rendered);
    }
}
