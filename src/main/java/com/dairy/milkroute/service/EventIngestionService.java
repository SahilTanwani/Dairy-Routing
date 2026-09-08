package com.dairy.milkroute.service;

import com.dairy.milkroute.config.ClockProvider;
import com.dairy.milkroute.domain.trip.EventReplayer;
import com.dairy.milkroute.domain.trip.ReplayableEvent;
import com.dairy.milkroute.domain.trip.TripStateMachine;
import com.dairy.milkroute.dto.request.DriverEventRequest;
import com.dairy.milkroute.dto.response.IngestResult;
import com.dairy.milkroute.entity.Trip;
import com.dairy.milkroute.entity.TripStop;
import com.dairy.milkroute.enums.EventType;
import com.dairy.milkroute.error.ClockDriftException;
import com.dairy.milkroute.error.TripNotFoundException;
import com.dairy.milkroute.repository.DriverEventRepository;
import com.dairy.milkroute.repository.TripRepository;
import com.dairy.milkroute.repository.TripStopRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Takes a batch of driver events and makes it true exactly once.
 *
 * <p>This is the piece the offline story rests on. A driver loses signal for forty minutes,
 * the phone keeps recording, and when the bars come back it sends everything it has —
 * including events the server already took, because the phone has no way to know which of
 * them got through before the drop. Sending too much is the correct behaviour; the server's
 * job is to absorb it without paying a farmer twice.
 *
 * <h2>Two things make that work</h2>
 *
 * <p><strong>The batch is sorted by {@code clientTs} before anything is replayed.</strong>
 * Events arrive in whatever order the phone flushed them, and a trip state machine fed an
 * arrival after a departure would refuse perfectly good events. Order comes from when things
 * happened, never from when they landed.
 *
 * <p><strong>The unique index on {@code client_event_id} decides what is new</strong>, via
 * an insert that lets Postgres absorb the conflict. The obvious version — save, catch
 * {@code DataIntegrityViolationException}, count it as a duplicate — reads well and does not
 * work: Postgres aborts the transaction on a failed statement, so the twenty-nine good
 * events behind the duplicate die with it. Pushing the conflict into the insert keeps the
 * index as the arbiter, keeps the transaction usable, and stays correct if two copies of the
 * same batch arrive at the same instant.
 *
 * <p>One transaction for the batch, so a batch either lands or does not. A partially applied
 * batch would leave a trip in a state no sequence of real events could have produced.
 */
@Service
public class EventIngestionService {

    /**
     * How far ahead of the server a phone's clock may be before its events are simply
     * clamped. Small skew is normal and harmless.
     */
    private static final Duration TOLERATED_DRIFT = Duration.ofMinutes(10);

    /**
     * And how far before the batch is refused outright. A clock an hour out would write a
     * {@code first_collection_at} an hour wrong, and a spoilage deadline computed from it
     * would be confidently, silently incorrect — worse than having no deadline at all.
     */
    private static final Duration REJECTED_DRIFT = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final TripRepository tripRepo;
    private final TripStopRepository tripStopRepo;
    private final DriverEventRepository eventRepo;
    private final CollectionRecordingService collections;
    private final ClockProvider clock;
    private final ObjectMapper objectMapper;

    public EventIngestionService(TripRepository tripRepo,
                                 TripStopRepository tripStopRepo,
                                 DriverEventRepository eventRepo,
                                 CollectionRecordingService collections,
                                 ClockProvider clock,
                                 ObjectMapper objectMapper) {
        this.tripRepo = tripRepo;
        this.tripStopRepo = tripStopRepo;
        this.eventRepo = eventRepo;
        this.collections = collections;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IngestResult ingest(long tripId, List<DriverEventRequest> batch) {
        Trip trip = tripRepo.findById(tripId)
                .orElseThrow(() -> new TripNotFoundException("no trip with id " + tripId));

        Instant now = clock.now();
        rejectIfClockIsBadlyWrong(batch, now);

        List<TripStop> stops = tripStopRepo.findByTripIdOrderBySeqAsc(tripId);
        EventReplayer replayer = new EventReplayer(new TripStateMachine(), collections);

        List<DriverEventRequest> inOrder = new ArrayList<>(batch);
        inOrder.sort(Comparator.comparing(DriverEventRequest::clientTs));

        int applied = 0;
        int duplicates = 0;

        for (DriverEventRequest request : inOrder) {
            EventType type = parseType(request);
            Instant clientTs = clamped(request.clientTs(), now);
            TripStop stop = stopFor(stops, request.seq());

            int inserted = eventRepo.insertIfAbsent(
                    request.clientEventId(),
                    tripId,
                    stop == null ? null : stop.getId(),
                    type.name(),
                    clientTs,
                    now,
                    payloadOf(request));

            if (inserted == 0) {
                duplicates++;
                continue;
            }

            replayer.apply(trip, stops, toReplayable(request, type, clientTs));
            applied++;
        }

        tripRepo.save(trip);
        tripStopRepo.saveAll(stops);

        return new IngestResult(
                batch.size(), applied, duplicates,
                trip.getStatus().name(), trip.getCurrentSeq());
    }

    /**
     * A phone whose clock is an hour out is not reporting a late event, it is reporting a
     * wrong one. Refusing the batch and telling the app to resync is the only answer that
     * does not corrupt a spoilage deadline.
     */
    private void rejectIfClockIsBadlyWrong(List<DriverEventRequest> batch, Instant now) {
        for (DriverEventRequest request : batch) {
            Duration ahead = Duration.between(now, request.clientTs());
            if (ahead.compareTo(REJECTED_DRIFT) > 0) {
                throw new ClockDriftException(
                        ("event %s is timestamped %d minutes ahead of the server; "
                                + "resync the device clock and resend")
                                .formatted(request.clientEventId(), ahead.toMinutes()));
            }
        }
    }

    /** Small skew is clamped to the server's clock and noted, not rejected. */
    private Instant clamped(Instant clientTs, Instant now) {
        if (clientTs.isAfter(now)) {
            Duration ahead = Duration.between(now, clientTs);
            if (ahead.compareTo(TOLERATED_DRIFT) > 0) {
                log.warn("Client clock {} minutes ahead of the server; clamping", ahead.toMinutes());
            }
            return now;
        }
        return clientTs;
    }

    private ReplayableEvent toReplayable(DriverEventRequest request,
                                         EventType type,
                                         Instant clientTs) {
        Map<String, BigDecimal> litres = new HashMap<>();
        if (request.collections() != null) {
            request.collections().forEach(entry -> litres.put(entry.farmerCode(), entry.litres()));
        }
        return new ReplayableEvent(type, clientTs, request.seq(), litres, request.skipReason());
    }

    private static TripStop stopFor(List<TripStop> stops, Integer seq) {
        if (seq == null) {
            return null;
        }
        return stops.stream().filter(stop -> stop.getSeq() == seq).findFirst().orElse(null);
    }

    private static EventType parseType(DriverEventRequest request) {
        try {
            return EventType.valueOf(request.type().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "unknown event type '%s' on event %s"
                            .formatted(request.type(), request.clientEventId()));
        }
    }

    /**
     * The original request, kept as JSON alongside the row.
     *
     * <p>Stored because six months from now, when a farmer disputes a figure, the argument is
     * settled by what the phone actually sent rather than by what the system made of it.
     */
    private String payloadOf(DriverEventRequest request) {
        return objectMapper.writeValueAsString(request);
    }
}
