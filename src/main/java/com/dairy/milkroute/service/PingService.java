package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.dto.request.TankerPingRequest;
import com.dairy.milkroute.entity.TankerPing;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.error.TripNotFoundException;
import com.dairy.milkroute.repository.TankerPingRepository;
import com.dairy.milkroute.repository.TripRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes GPS readings and keeps the trip's last known position current.
 *
 * <p><strong>No idempotency key, deliberately.</strong> A duplicate position is harmless:
 * pings observe, events change things. Stamping a UUID on the highest-volume write in the
 * system — roughly eight thousand rows a session — to guard against a duplicate that costs
 * nothing would be paying a real price for no benefit. The contrast with
 * {@code driver_event}, where a duplicate would pay a farmer twice, is the point.
 *
 * <p>The denormalised position on {@code trip} is what the ops board reads. Twenty-two live
 * trips polled every couple of seconds cannot each run an aggregate over a ping table.
 *
 * <p>Only a reading newer than the one already stored moves it. A batch flushed after a dead
 * zone arrives in whatever order the phone had it, and an older fix must not drag a tanker
 * backwards across the board.
 */
@Service
public class PingService {

    private final TripRepository tripRepo;
    private final TankerPingRepository pingRepo;
    private final ClockProvider clock;

    public PingService(TripRepository tripRepo,
                       TankerPingRepository pingRepo,
                       ClockProvider clock) {
        this.tripRepo = tripRepo;
        this.pingRepo = pingRepo;
        this.clock = clock;
    }

    @Transactional
    public int record(long tripId, List<TankerPingRequest> batch) {
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

            if (trip.getLastPingAt() == null
                    || request.recordedAt().isAfter(trip.getLastPingAt())) {
                trip.setLastLat(request.lat());
                trip.setLastLng(request.lng());
                trip.setLastPingAt(request.recordedAt());
            }
        }

        pingRepo.saveAll(rows);
        tripRepo.save(trip);
        return rows.size();
    }
}
