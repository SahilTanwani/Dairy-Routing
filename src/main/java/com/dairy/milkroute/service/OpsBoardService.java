package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.dto.response.OpsBoardResponse;
import com.dairy.milkroute.entity.Alert;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.enums.AlertSeverity;
import com.dairy.milkroute.enums.RiskLevel;
import com.dairy.milkroute.enums.TripStatus;
import com.dairy.milkroute.enums.TripStopStatus;
import com.dairy.milkroute.repository.TripRepository;
import com.dairy.milkroute.repository.TripStopRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the dispatcher's board.
 *
 * <p>One call rather than four. Beyond saving requests, it means every panel on the screen
 * describes the same instant: a board stitched from separately-timed responses can show a
 * trip as comfortable in one place and critical in another, and the dispatcher has no way to
 * tell which is stale.
 *
 * <p>Everything read here is denormalised onto {@code trip} by the monitor and the ping
 * service. Twenty-two live trips polled every couple of seconds must not each trigger a route
 * walk through the travel model.
 */
@Service
public class OpsBoardService {

    private final TripRepository tripRepo;
    private final TripStopRepository tripStopRepo;
    private final AlertService alerts;
    private final ClockProvider clock;

    public OpsBoardService(TripRepository tripRepo,
                           TripStopRepository tripStopRepo,
                           AlertService alerts,
                           ClockProvider clock) {
        this.tripRepo = tripRepo;
        this.tripStopRepo = tripStopRepo;
        this.alerts = alerts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OpsBoardResponse board() {
        Instant now = clock.now();

        List<Trip> active = tripRepo.findByStatusIn(List.of(
                TripStatus.SCHEDULED, TripStatus.IN_PROGRESS,
                TripStatus.RETURNING, TripStatus.AT_PLANT, TripStatus.BREAKDOWN));

        List<OpsBoardResponse.TripCard> cards = active.stream()
                .map(trip -> toCard(trip, now))
                // Worst first. A board ordered by route label makes the dispatcher hunt for
                // the emergency, which is the one thing they should never have to do.
                .sorted(Comparator
                        .comparingInt((OpsBoardResponse.TripCard card) ->
                                RiskLevel.valueOf(card.riskLevel()).ordinal())
                        .reversed()
                        .thenComparing(card -> card.minutesToSpoil() == null
                                ? Long.MAX_VALUE : card.minutesToSpoil()))
                .toList();

        List<OpsBoardResponse.AlertCard> openAlerts = alerts.open().stream()
                .map(OpsBoardService::toCard)
                .toList();

        return new OpsBoardResponse(
                now,
                cards,
                alerts.openCount(AlertSeverity.CRITICAL),
                alerts.openCount(AlertSeverity.WARNING),
                openAlerts);
    }

    private OpsBoardResponse.TripCard toCard(Trip trip, Instant now) {
        List<TripStop> stops = tripStopRepo.findByTripIdOrderBySeqAsc(trip.getId());
        long collected = stops.stream()
                .filter(stop -> stop.getStatus() == TripStopStatus.COLLECTED)
                .count();

        // Minutes of budget left. Negative once the deadline has gone, which is exactly the
        // number a dispatcher needs and the one a raw timestamp hides.
        Long minutesToSpoil = trip.getSpoilageDeadlineAt() == null
                ? null
                : Duration.between(now, trip.getSpoilageDeadlineAt()).toMinutes();

        return new OpsBoardResponse.TripCard(
                trip.getId(),
                trip.getRoute().getLabel(),
                trip.getTanker().getRegNo(),
                trip.getTanker().isInsulated(),
                trip.getDriver().getCode(),
                trip.getStatus().name(),
                trip.getRiskLevel().name(),
                trip.getCurrentSeq(),
                stops.size(),
                (int) collected,
                trip.getLitresOnBoard(),
                trip.getSpoilageDeadlineAt(),
                trip.getEtaPlantAt(),
                trip.getEtaConfidence().name(),
                minutesToSpoil,
                trip.getLastPingAt());
    }

    private static OpsBoardResponse.AlertCard toCard(Alert alert) {
        return new OpsBoardResponse.AlertCard(
                alert.getId(),
                alert.getTrip() == null ? null : alert.getTrip().getId(),
                alert.getAlertType().name(),
                alert.getSeverity().name(),
                alert.getMessage(),
                alert.getRaisedAt());
    }
}
